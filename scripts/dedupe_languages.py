#!/usr/bin/env python3
"""
dedupe_languages.py -- find and remove duplicate experiment languages and duplicate
translations in a Breadboard install's H2 database, with a preview -> review -> apply flow.

Background: createExperiment used to attach the user's default language to a new experiment
twice, and the experiments_languages join table had no key to stop it -- so some experiments
list e.g. "English" twice in the content dialog. With a language listed twice, the content
dialog also creates one translation per listed language, so a content item can end up with
several translations in the SAME language. Both break export and update.

  preview  Writes a plan to <out>/plan.csv, plus the full html of every duplicated translation
           under <out>/translations/ so you can compare versions. Changes nothing.
  apply    Re-checks the database against the (possibly hand-edited) plan, shows what will
           change, backs up the database, and applies the plan in a single transaction.

plan.csv has one row per item, with an `action` column you may edit:

  type=link         One row per duplicated (experiment, language) link. The copies are
                    identical, so nothing is lost.  action: dedupe (keep one) | skip
  type=translation  One row per translation in a (content, language) group that has more
                    than one translation.           action: keep | delete
                    Translations have no timestamps, so by default the LONGEST one in each
                    group (ignoring surrounding whitespace) is kept and the rest deleted;
                    ties go to the lowest id. Groups that had two or more DIFFERENT non-blank
                    versions get a `note` on each row, so you can review just those (compare
                    the files under translations/) and swap keep/delete where needed.
                    Each group must end up with exactly one `keep`. Set any row in a group to
                    CONFLICT to leave that whole group untouched on apply.

apply refuses to run (changing nothing) if any edited action is invalid, or if a group's
translations were added, removed or edited since the preview -- re-run preview in that case.

Usage:
  python3 dedupe_languages.py preview --install /path/to/breadboard [--out DIR]
  python3 dedupe_languages.py apply   --install /path/to/breadboard --plan DIR/plan.csv
                                      [--yes] [--no-backup]

  --install    Breadboard install dir (contains lib/, db/). Required.
  --out        Where preview writes the plan (default: ./dedupe-plan-<timestamp>).
  --plan       The plan.csv to apply.
  --yes        Don't ask for confirmation before applying.
  --no-backup  Skip copying db/breadboard.h2.db to db/breadboard.h2.db.<timestamp>.bak first.
  --h2-jar     Override the H2 jar location (default: <install>/lib/*h2*.jar).
  --java       Java executable (default: java on PATH).

Requires: the server STOPPED (the H2 file database is locked while it runs), Java, and
Python 3.9+ (standard library only).
"""

import argparse
import csv
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections import OrderedDict
from datetime import datetime
from pathlib import Path

PLAN_COLUMNS = [
    "type", "action", "note", "experiment_id", "experiment_name", "content_id", "content_name",
    "language_id", "language_code", "translation_id", "copies", "chars", "sha256",
    "preview", "html_file",
]

LINKS_SQL = """
SELECT el.experiments_id AS experiment_id, e.name AS experiment_name,
       el.languages_id AS language_id, l.code AS language_code, COUNT(*) AS copies
FROM experiments_languages el
JOIN experiments e ON e.id = el.experiments_id
JOIN languages l ON l.id = el.languages_id
GROUP BY el.experiments_id, e.name, el.languages_id, l.code
HAVING COUNT(*) > 1
ORDER BY el.experiments_id, l.code
"""

DUP_TRANSLATIONS_SQL = """
SELECT c.experiment_id AS experiment_id, e.name AS experiment_name,
       t.content_id AS content_id, c.name AS content_name,
       t.languages_id AS language_id, l.code AS language_code,
       t.id AS translation_id, t.html AS html
FROM translations t
JOIN (SELECT content_id, languages_id FROM translations
      GROUP BY content_id, languages_id HAVING COUNT(*) > 1) g
  ON g.content_id = t.content_id AND g.languages_id = t.languages_id
JOIN content c ON c.id = t.content_id
JOIN experiments e ON e.id = c.experiment_id
JOIN languages l ON l.id = t.languages_id
ORDER BY c.experiment_id, c.name, l.code, t.id
"""

DUP_CODES_SQL = """
SELECT code AS code, GROUP_CONCAT(id) AS ids FROM languages
GROUP BY code HAVING COUNT(*) > 1
"""


def die(msg, code=1):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(code)


class H2:
    """Runs SQL against the install's H2 file database via the bundled H2 jar."""

    def __init__(self, install, h2_jar, java):
        self.install = Path(install).resolve()
        self.db_file = self.install / "db" / "breadboard.h2.db"
        if not self.db_file.is_file():
            die(f"database not found at {self.db_file}")
        if h2_jar is None:
            jars = sorted(glob.glob(str(self.install / "lib" / "*h2*.jar")))
            if not jars:
                die(f"H2 jar not found under {self.install / 'lib'} (use --h2-jar)")
            h2_jar = jars[0]
        self.h2_jar = str(Path(h2_jar).resolve())
        self.java = java
        if shutil.which(java) is None:
            die(f"'{java}' not found (use --java)")
        db_base = (self.install / "db" / "breadboard").as_posix()
        self.url = f"jdbc:h2:{db_base};MODE=MYSQL;IFEXISTS=TRUE"
        self.tmp = tempfile.TemporaryDirectory(prefix="bb-dedupe-")

    def run_script(self, sql):
        # Every statement this script builds is ASCII (ids, fixed SQL), which matters because
        # H2 1.3's RunScript reads the file in the platform charset and has no -charset flag.
        script = Path(self.tmp.name) / "run.sql"
        script.write_text(sql, encoding="ascii")
        proc = subprocess.run(
            [self.java, "-cp", self.h2_jar, "org.h2.tools.RunScript", "-url", self.url,
             "-user", "", "-password", "", "-script", str(script)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if proc.returncode != 0:
            out = proc.stdout
            if "already in use" in out or "Locked by another process" in out:
                die("the database is locked -- stop Breadboard first.")
            die("SQL failed:\n    " + out.strip().replace("\n", "\n    "))

    def query(self, sql):
        """Run a SELECT and return its rows as dicts with lower-case keys."""
        out = Path(self.tmp.name) / "query.csv"
        if out.exists():
            out.unlink()
        inner = sql.strip().replace("'", "''")
        self.run_script(f"CALL CSVWRITE('{out.as_posix()}', '{inner}', 'charset=UTF-8');\n")
        with open(out, encoding="utf-8", newline="") as f:
            return [{k.lower(): v for k, v in row.items()} for row in csv.DictReader(f)]


def check_not_running(install):
    pid_file = Path(install) / "RUNNING_PID"
    # os.kill(pid, 0) is a liveness probe on POSIX only (on Windows it would kill the
    # process). On Windows we rely on H2's own file lock, which run_script reports.
    if pid_file.is_file() and os.name == "posix":
        try:
            pid = int(pid_file.read_text().strip())
            os.kill(pid, 0)
        except (ValueError, ProcessLookupError):
            return
        except PermissionError:
            pass
        die(f"Breadboard appears to be running (PID {pid}). Stop it first -- the H2 file "
            "database is locked while the server is up.")


def is_blank(html):
    return html is None or html.strip() == ""


def sha256(html):
    return "sha256:" + hashlib.sha256((html or "").encode("utf-8")).hexdigest()


def preview_text(html, width=60):
    text = re.sub(r"\s+", " ", html or "").strip()
    return text if len(text) <= width else text[:width - 3] + "..."


def safe_name(s):
    return re.sub(r"[^A-Za-z0-9._-]+", "_", s or "").strip("_")[:60] or "content"


def group_translations(rows):
    groups = OrderedDict()
    for r in rows:
        groups.setdefault((r["content_id"], r["language_id"]), []).append(r)
    return groups


def suggest_actions(group):
    """Return ({translation_id: action}, note) for one (content, language) group.

    Keeps the longest translation (by stripped length, ties to the lowest id). The note is
    non-empty only when the group held different non-blank versions, i.e. when picking the
    longest actually discarded distinct content and is worth a human look.
    """
    def size(r):
        return len((r["html"] or "").strip())

    keeper = min(group, key=lambda r: (-size(r), int(r["translation_id"])))
    actions = {r["translation_id"]: "delete" for r in group}
    actions[keeper["translation_id"]] = "keep"

    versions = {(r["html"] or "").strip() for r in group if not is_blank(r["html"])}
    note = ""
    if len(versions) > 1:
        tied = sum(1 for r in group if size(r) == size(keeper)
                   and (r["html"] or "").strip() != (keeper["html"] or "").strip())
        note = f"{len(versions)} different versions - kept longest"
        if tied:
            note += " (tie on length - kept lowest id)"
    return actions, note


# --- preview -------------------------------------------------------------------------------

def cmd_preview(args):
    check_not_running(args.install)
    h2 = H2(args.install, args.h2_jar, args.java)
    out_dir = Path(args.out or f"dedupe-plan-{datetime.now():%Y%m%d-%H%M%S}").resolve()
    if (out_dir / "plan.csv").exists():
        die(f"{out_dir / 'plan.csv'} already exists -- pick another --out")
    (out_dir / "translations").mkdir(parents=True, exist_ok=True)

    links = h2.query(LINKS_SQL)
    translations = h2.query(DUP_TRANSLATIONS_SQL)
    dup_codes = h2.query(DUP_CODES_SQL)

    plan = []
    for l in links:
        plan.append({**{c: "" for c in PLAN_COLUMNS}, "type": "link", "action": "dedupe",
                     "experiment_id": l["experiment_id"], "experiment_name": l["experiment_name"],
                     "language_id": l["language_id"], "language_code": l["language_code"],
                     "copies": l["copies"]})

    counts = {"keep": 0, "delete": 0}
    review_groups = 0
    for (content_id, language_id), group in group_translations(translations).items():
        actions, note = suggest_actions(group)
        review_groups += bool(note)
        first = group[0]
        group_dir = Path("translations") / safe_name(
            f"{first['experiment_id']}-{first['content_name']}-{first['language_code']}")
        (out_dir / group_dir).mkdir(parents=True, exist_ok=True)
        for r in sorted(group, key=lambda r: int(r["translation_id"])):
            html_file = group_dir / f"{r['translation_id']}.html"
            with open(out_dir / html_file, "w", encoding="utf-8", newline="") as f:
                f.write(r["html"] or "")
            action = actions[r["translation_id"]]
            counts[action] += 1
            plan.append({
                "type": "translation", "action": action, "note": note,
                "experiment_id": r["experiment_id"], "experiment_name": r["experiment_name"],
                "content_id": content_id, "content_name": r["content_name"],
                "language_id": language_id, "language_code": r["language_code"],
                "translation_id": r["translation_id"], "copies": "",
                "chars": len(r["html"] or ""), "sha256": sha256(r["html"]),
                "preview": preview_text(r["html"]), "html_file": html_file.as_posix(),
            })

    # utf-8-sig so Excel detects the encoding; apply reads it back either way.
    with open(out_dir / "plan.csv", "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=PLAN_COLUMNS)
        w.writeheader()
        w.writerows(plan)

    print(f"Database: {h2.db_file}")
    print()
    print("Duplicate experiment languages:")
    for l in links:
        print(f"    experiment {l['experiment_id']} ({l['experiment_name']}): "
              f"{l['language_code']} x{l['copies']} -> dedupe")
    if not links:
        print("    (none)")
    print()
    print("Duplicate translations (experiment | content | language | id | chars | action | preview):")
    for p in plan:
        if p["type"] == "translation":
            flag = " *" if p["note"] else ""
            print(f"    {p['experiment_id']} | {p['content_name']} | {p['language_code']} | "
                  f"{p['translation_id']} | {p['chars']} | {p['action']}{flag} | {p['preview']}")
    if not translations:
        print("    (none)")
    if review_groups:
        print()
        print(f"NOTE: {review_groups} group(s), marked *, had different versions; the longest was")
        print("      kept. Worth a look: compare their files under translations/ and swap")
        print("      keep/delete in plan.csv if a shorter version is the right one.")
    if dup_codes:
        print()
        print("WARNING: the languages table has more than one row for the same code. This script")
        print("         does NOT fix that -- references need repointing to one id by hand:")
        for d in dup_codes:
            print(f"    {d['code']}: ids {d['ids']}")
    print()
    print(f"Plan written to {out_dir / 'plan.csv'}")
    print(f"  {len(links)} link(s) to dedupe; translations: {counts['keep']} keep, "
          f"{counts['delete']} delete ({review_groups} group(s) worth reviewing)")
    if not plan:
        print("Nothing to do.")
        return
    print("Review/edit the `action` column, then run:")
    print(f"  python3 {Path(sys.argv[0]).name} apply --install {args.install} "
          f"--plan {out_dir / 'plan.csv'}")


# --- apply ---------------------------------------------------------------------------------

def read_plan(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        missing = {"type", "action", "experiment_id", "language_id", "content_id",
                   "translation_id", "sha256"} - set(reader.fieldnames or [])
        if missing:
            die(f"{path} is missing column(s): {', '.join(sorted(missing))}")
        return [{k: (v or "").strip() for k, v in row.items() if k} for row in reader]


def cmd_apply(args):
    check_not_running(args.install)
    plan = read_plan(args.plan)
    h2 = H2(args.install, args.h2_jar, args.java)
    errors = []

    # Links: act only on pairs that are still duplicated.
    current_links = {(l["experiment_id"], l["language_id"]): l for l in h2.query(LINKS_SQL)}
    link_fixes = []
    for i, row in enumerate(plan, start=2):  # line 1 is the header
        if row["type"] != "link":
            continue
        action = row["action"].lower()
        if action not in ("dedupe", "skip"):
            errors.append(f"line {i}: link action must be dedupe or skip, not '{row['action']}'")
        elif action == "dedupe" and (row["experiment_id"], row["language_id"]) in current_links:
            link_fixes.append(current_links[(row["experiment_id"], row["language_id"])])

    # Translations: validate each group against the edited actions and the live database.
    t_rows = [(i, r) for i, r in enumerate(plan, start=2) if r["type"] == "translation"]
    groups = OrderedDict()
    for i, r in t_rows:
        groups.setdefault((r["content_id"], r["language_id"]), []).append((i, r))
    for i, r in enumerate(plan, start=2):
        if r["type"] not in ("link", "translation"):
            errors.append(f"line {i}: unknown type '{r['type']}'")

    live = {}
    content_ids = sorted({cid for cid, _ in groups if cid.isdigit()}, key=int)
    bad_ids = [cid for cid, _ in groups if not cid.isdigit()]
    if bad_ids:
        errors.append(f"invalid content_id value(s): {', '.join(bad_ids)}")
    if content_ids:
        rows = h2.query("SELECT id AS translation_id, content_id AS content_id, "
                        "languages_id AS language_id, html AS html FROM translations "
                        f"WHERE content_id IN ({', '.join(content_ids)})")
        for r in rows:
            live.setdefault((r["content_id"], r["language_id"]), {})[r["translation_id"]] = r

    to_delete, skipped = [], []
    for key, members in groups.items():
        first = members[0][1]
        label = (f"experiment {first['experiment_id']} / '{first.get('content_name', '')}' / "
                 f"{first.get('language_code') or 'language ' + key[1]}")
        actions = {r["translation_id"]: r["action"] for _, r in members}
        normalized = {tid: a.lower() for tid, a in actions.items()}
        if any(a == "conflict" for a in normalized.values()):
            skipped.append(label)
            continue
        invalid = [f"line {i}: '{r['action']}'" for i, r in members
                   if r["action"].lower() not in ("keep", "delete")]
        if invalid:
            errors.append(f"{label}: action must be keep or delete ({'; '.join(invalid)})")
            continue
        keeps = [tid for tid, a in normalized.items() if a == "keep"]
        if len(keeps) != 1:
            errors.append(f"{label}: needs exactly one keep, has {len(keeps)}")
            continue
        current = live.get(key, {})
        if set(current) != set(actions):
            errors.append(f"{label}: translations changed since preview "
                          f"(plan ids {sorted(actions, key=int)}, now {sorted(current, key=int)})")
            continue
        edited = [r["translation_id"] for _, r in members
                  if sha256(current[r["translation_id"]]["html"]) != r["sha256"]]
        if edited:
            errors.append(f"{label}: translation(s) {', '.join(edited)} edited since preview")
            continue
        for _, r in members:
            if r["action"].lower() == "delete":
                to_delete.append((label, r))

    if errors:
        print("Plan NOT applied -- nothing was changed:", file=sys.stderr)
        for e in errors:
            print("    " + e, file=sys.stderr)
        print("Fix the plan (or re-run preview if the database changed) and try again.",
              file=sys.stderr)
        sys.exit(1)

    print(f"Database: {h2.db_file}")
    print()
    print("Will dedupe experiment languages:")
    for l in link_fixes:
        print(f"    experiment {l['experiment_id']} ({l['experiment_name']}): "
              f"{l['language_code']} x{l['copies']} -> 1")
    if not link_fixes:
        print("    (none)")
    print()
    print("Will delete translations:")
    for label, r in to_delete:
        print(f"    {r['translation_id']}  ({label}, {r.get('chars', '?')} chars) {r.get('preview', '')}")
    if not to_delete:
        print("    (none)")
    if skipped:
        print()
        print("Skipping groups still marked CONFLICT:")
        for s in skipped:
            print("    " + s)

    if not link_fixes and not to_delete:
        print()
        print("Nothing to apply.")
        return

    if not args.yes:
        if not sys.stdin.isatty():
            die("not a terminal -- pass --yes to apply without confirmation")
        try:
            answer = input("\nApply these changes? [y/N] ")
        except EOFError:
            answer = ""
        if answer.strip().lower() not in ("y", "yes"):
            print("\nAborted; nothing was changed.")
            return

    if not args.no_backup:
        backup = h2.db_file.with_name(f"{h2.db_file.name}.{datetime.now():%Y%m%d-%H%M%S}.bak")
        shutil.copy2(h2.db_file, backup)
        print(f"Backed up database to {backup}")

    # One transaction: if any statement fails, RunScript exits without COMMIT and H2 rolls
    # the whole thing back on close.
    sql = ["SET AUTOCOMMIT FALSE;"]
    for l in link_fixes:
        e, lang = int(l["experiment_id"]), int(l["language_id"])
        sql.append(f"DELETE FROM experiments_languages WHERE experiments_id = {e} "
                   f"AND languages_id = {lang};")
        sql.append(f"INSERT INTO experiments_languages (experiments_id, languages_id) "
                   f"VALUES ({e}, {lang});")
    if to_delete:
        ids = ", ".join(str(int(r["translation_id"])) for _, r in to_delete)
        sql.append(f"DELETE FROM translations WHERE id IN ({ids});")
    sql.append("COMMIT;")
    h2.run_script("\n".join(sql) + "\n")

    # Verify.
    still_dup = {(l["experiment_id"], l["language_id"]) for l in h2.query(LINKS_SQL)}
    fixed = {(l["experiment_id"], l["language_id"]) for l in link_fixes}
    remaining = []
    if to_delete:
        ids = ", ".join(str(int(r["translation_id"])) for _, r in to_delete)
        remaining = h2.query(f"SELECT id AS id FROM translations WHERE id IN ({ids})")
    if still_dup & fixed or remaining:
        die("verification failed -- some rows were not removed. Check the database "
            "(the backup above is the way back).")
    print(f"OK: deduped {len(link_fixes)} experiment language link(s) and deleted "
          f"{len(to_delete)} translation(s).")
    if skipped:
        print(f"    {len(skipped)} CONFLICT group(s) left untouched.")


def main():
    parser = argparse.ArgumentParser(
        description="Preview and remove duplicate experiment languages/translations "
                    "in a Breadboard H2 database.",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("preview", "apply"):
        p = sub.add_parser(name)
        p.add_argument("--install", required=True, help="Breadboard install dir (contains lib/, db/)")
        p.add_argument("--h2-jar", help="H2 jar (default: <install>/lib/*h2*.jar)")
        p.add_argument("--java", default="java", help="Java executable (default: java on PATH)")
        if name == "preview":
            p.add_argument("--out", help="output dir (default: ./dedupe-plan-<timestamp>)")
        else:
            p.add_argument("--plan", required=True, help="plan.csv written by preview")
            p.add_argument("--yes", action="store_true", help="don't ask for confirmation")
            p.add_argument("--no-backup", action="store_true", help="skip the database backup")
    args = parser.parse_args()
    # Don't crash printing html previews the console's code page can't encode (e.g. Windows).
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
    if not Path(args.install).is_dir():
        die("--install must point to a Breadboard install dir", 2)
    if args.command == "preview":
        cmd_preview(args)
    else:
        cmd_apply(args)


if __name__ == "__main__":
    main()
