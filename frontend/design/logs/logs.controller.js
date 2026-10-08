/**
 * Logs dialog: a sliding window over the server log.
 *
 * Nothing is fetched until the dialog is opened. At most MAX_WINDOW entries are held in the
 * page; scrolling to the top pages older entries in from the server (and drops the newest),
 * scrolling to the bottom pages newer ones back in. While following, new entries are polled
 * every POLL_MS. Search and level filters run on the server over the whole log, so they find
 * entries that were never loaded into the page.
 */
const PAGE = 200;
const MAX_WINDOW = 2000;
const POLL_MS = 2000;
const EDGE_PX = 40;
// Empty pages a filtered search may chain through (the server scans a bounded slice per request)
// before waiting for the user to ask for more.
const MAX_EMPTY_CHAIN = 20;

function LogsCtrl($scope, $element, $timeout, LogsSrv) {
  const vm = this;
  vm.entries = [];
  vm.query = '';
  vm.level = '';
  vm.activeQuery = '';
  vm.activeLevel = '';
  vm.follow = true;
  vm.hasOlder = false;
  vm.hasNewer = false;
  vm.loading = false;
  vm.enabled = true;
  vm.error = null;
  vm.expanded = {};
  vm.highlightSeq = null;

  vm.search = search;
  vm.clearSearch = clearSearch;
  vm.loadOlder = loadOlder;
  vm.loadNewer = loadNewer;
  vm.jumpToLatest = jumpToLatest;
  vm.showInContext = showInContext;
  vm.toggle = toggle;
  vm.isFiltered = isFiltered;

  let oldestCursor = null;  // pass as `before` to page older
  let newestCursor = null;  // pass as `after` to page newer
  let generation = 0;       // bumped on every reset; responses from older generations are dropped
  let loaded = false;
  let list = null;

  $timeout(function () {
    list = $element[0].querySelector('.logs-list');
    list.addEventListener('scroll', onScroll);
  });

  const pollTimer = window.setInterval(tick, POLL_MS);
  // Load as soon as the dialog opens rather than waiting for the next poll tick.
  const dialog = $element.closest('[ui-jq="dialog"]');
  dialog.on('dialogopen', onOpen);

  $scope.$on('$destroy', function () {
    window.clearInterval(pollTimer);
    dialog.off('dialogopen', onOpen);
    if (list) list.removeEventListener('scroll', onScroll);
  });

  function isVisible() {
    return $element.is(':visible') && !document.hidden;
  }

  function onOpen() {
    $timeout(tick);
  }

  function tick() {
    if (!isVisible() || vm.loading) return;
    if (!loaded) {
      reset();
    } else if (vm.follow && !vm.hasNewer) {
      // Also catches up after the dialog was closed for a while (see loadNewer).
      loadNewer(true);
    }
  }

  function params(extra) {
    return angular.extend({
      limit: PAGE,
      q: vm.activeQuery || undefined,
      level: vm.activeLevel || undefined
    }, extra);
  }

  function prepare(entries) {
    entries.forEach(function (e) {
      const nl = e.msg ? e.msg.indexOf('\n') : -1;
      e.first = nl === -1 ? e.msg : e.msg.substring(0, nl);
      e.extraLines = nl === -1 ? 0 : e.msg.split('\n').length - 1;
      e.time = formatTime(e.ts);
    });
    return entries;
  }

  function request(extra) {
    const gen = generation;
    vm.loading = true;
    vm.error = null;
    return LogsSrv.getLogs(params(extra)).then(function (data) {
      if (gen !== generation) return null;
      vm.loading = false;
      vm.enabled = data.enabled !== false;
      if (!vm.enabled) return null;
      prepare(data.entries);
      return data;
    }, function (response) {
      if (gen !== generation) return null;
      vm.loading = false;
      vm.error = 'Could not load logs' + (response && response.status ? ' (' + response.status + ')' : '');
      return null;
    });
  }

  /** Load the newest window for the current filter, or the window ending just before `before`. */
  function reset(before, emptyChain) {
    generation++;
    loaded = true;
    vm.expanded = {};
    return request(before ? { before: before } : {}).then(function (data) {
      if (!data) return;
      vm.entries = data.entries;
      oldestCursor = data.cursor;
      vm.hasOlder = data.hasMore;
      newestCursor = before ? before - 1 : data.latestSeq;
      vm.hasNewer = newestCursor < data.latestSeq;
      if (!before) scrollToBottom();
      continueEmptySearch(data, emptyChain || 0);
    });
  }

  function loadOlder(emptyChain) {
    if (vm.loading || !vm.hasOlder) return;
    const anchor = vm.entries.length ? vm.entries[0].seq : null;
    const anchorOffset = anchor === null ? 0 : rowTop(anchor) - list.scrollTop;
    return request({ before: oldestCursor }).then(function (data) {
      if (!data) return;
      vm.entries = data.entries.concat(vm.entries);
      oldestCursor = data.cursor;
      vm.hasOlder = data.hasMore;
      if (vm.entries.length > MAX_WINDOW) {
        vm.entries = vm.entries.slice(0, MAX_WINDOW);
        newestCursor = vm.entries[vm.entries.length - 1].seq;
        vm.hasNewer = true;
        vm.follow = false;
      }
      // Keep the rows the user was looking at in place while older ones are inserted above.
      if (anchor !== null) {
        $timeout(function () {
          list.scrollTop = rowTop(anchor) - anchorOffset;
        });
      }
      continueEmptySearch(data, typeof emptyChain === 'number' ? emptyChain : 0);
    });
  }

  function loadNewer(polling) {
    if (vm.loading || newestCursor === null) return;
    const atBottom = isAtBottom();
    return request({ after: newestCursor }).then(function (data) {
      if (!data) return;
      if (polling === true && data.hasMore) {
        // Fell more than a page behind (e.g. the dialog was closed for a while): jump to live
        // instead of streaming everything we missed into the page.
        return reset();
      }
      vm.entries = vm.entries.concat(data.entries);
      newestCursor = data.cursor;
      vm.hasNewer = data.hasMore;
      if (vm.entries.length > MAX_WINDOW) {
        vm.entries = vm.entries.slice(vm.entries.length - MAX_WINDOW);
        oldestCursor = vm.entries[0].seq;
        vm.hasOlder = true;
      }
      if (vm.follow && atBottom && data.entries.length) scrollToBottom();
    });
  }

  /** A filtered search may come back empty while there is unscanned history left: keep going. */
  function continueEmptySearch(data, emptyChain) {
    if (isFiltered() && data.entries.length === 0 && data.hasMore && emptyChain < MAX_EMPTY_CHAIN) {
      loadOlder(emptyChain + 1);
    }
  }

  function search() {
    vm.activeQuery = vm.query;
    vm.activeLevel = vm.level;
    vm.follow = true;
    vm.highlightSeq = null;
    reset();
  }

  function clearSearch() {
    vm.query = '';
    vm.level = '';
    search();
  }

  function jumpToLatest() {
    vm.follow = true;
    vm.highlightSeq = null;
    reset();
  }

  /** From a search hit, show the unfiltered log around it. */
  function showInContext(entry) {
    vm.query = vm.activeQuery = '';
    vm.level = vm.activeLevel = '';
    vm.follow = false;
    vm.highlightSeq = entry.seq;
    reset(entry.seq + PAGE / 2).then(function () {
      $timeout(function () {
        const row = $element[0].querySelector('[data-seq="' + entry.seq + '"]');
        if (row) row.scrollIntoView({ block: 'center' });
      });
    });
  }

  function toggle(entry) {
    if (entry.extraLines) vm.expanded[entry.seq] = !vm.expanded[entry.seq];
  }

  function isFiltered() {
    return !!(vm.activeQuery || vm.activeLevel);
  }

  function rowTop(seq) {
    const row = list && list.querySelector('[data-seq="' + seq + '"]');
    return row ? row.offsetTop : 0;
  }

  function isAtBottom() {
    return !list || list.scrollHeight - list.scrollTop - list.clientHeight < EDGE_PX;
  }

  function scrollToBottom() {
    $timeout(function () {
      if (list) list.scrollTop = list.scrollHeight;
    });
  }

  function onScroll() {
    if (vm.loading) return;
    const atBottom = isAtBottom();
    if (list.scrollTop < EDGE_PX && vm.hasOlder) {
      $scope.$apply(function () { loadOlder(); });
    } else if (atBottom && vm.hasNewer) {
      $scope.$apply(function () { loadNewer(); });
    } else if (vm.follow !== (atBottom && !vm.hasNewer)) {
      // Scrolling up pauses following; scrolling back to the live end resumes it.
      $scope.$apply(function () { vm.follow = atBottom && !vm.hasNewer; });
    }
  }

  function pad(n, width) {
    n = String(n);
    while (n.length < (width || 2)) n = '0' + n;
    return n;
  }

  function formatTime(ts) {
    const d = new Date(ts);
    return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
      pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()) + '.' + pad(d.getMilliseconds(), 3);
  }
}

LogsCtrl.$inject = ['$scope', '$element', '$timeout', 'LogsSrv'];

export default LogsCtrl;
