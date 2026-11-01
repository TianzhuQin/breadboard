import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import models.*;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Session ids follow on after a crash (Global.uncacheSessionIds): their sequence keeps no numbers in
 * memory. With H2's default cache of 32, a server killed went on after the last batch it had taken, and
 * the next session's id jumped by up to 32. A database from an older release has its sequence made
 * again, going on from the last id handed out, or from the highest id; a missing one is made.
 */
public class SessionIdsTest extends BaseTest {

    @After
    public void uncachedAgain() {
        // a test that put the older sequence back leaves the one the server makes
        Global.uncacheSessionIds();
        assertEquals(Long.valueOf(1L), cache());
    }

    private static SqlRow sequence() {
        return Ebean.createSqlQuery("select current_value as current_value, cache as cache_size "
            + "from information_schema.sequences where lower(sequence_name) = 'experiment_instances_seq'").findUnique();
    }

    private static Long cache() {
        SqlRow s = sequence();
        return s == null ? null : s.getLong("cache_size");
    }

    /** The sequence as H2 makes it by default (as evolution 1 and every release before made it), at {@code start}. */
    private static void olderSequence(long start) {
        Ebean.createSqlUpdate("drop sequence if exists experiment_instances_seq").execute();
        Ebean.createSqlUpdate("create sequence experiment_instances_seq start with " + start).execute();
        assertEquals(Long.valueOf(32L), cache());
    }

    @Test
    public void theServerStartsWithASequenceThatKeepsNoNumbersInMemory() {
        assertEquals(Long.valueOf(1L), cache());
        Experiment experiment = createExperiment("Ids");
        ExperimentInstance first = createInstance("first", experiment);
        ExperimentInstance second = createInstance("second", experiment);
        assertEquals(first.id + 1, (long) second.id);
    }

    @Test
    public void anOlderSequenceIsMadeAgainGoingOnFromTheLastNumberItHandedOut() {
        Experiment experiment = createExperiment("Older sequence");
        ExperimentInstance before = createInstance("before", experiment);
        // (a crash had made it skip ahead: it goes on from there, never back)
        olderSequence(before.id + 40);
        Global.uncacheSessionIds();
        assertEquals(Long.valueOf(1L), cache());
        assertEquals(before.id + 40, (long) createInstance("after", experiment).id);
        // Nothing more to do once it is done.
        Global.uncacheSessionIds();
        assertEquals(before.id + 41, (long) createInstance("next", experiment).id);
    }

    @Test
    public void itGoesOnFromTheHighestIdWhenTheSequenceIsBehindAndIsMadeWhenMissing() {
        Experiment experiment = createExperiment("Behind");
        ExperimentInstance highest = createInstance("highest", experiment);
        olderSequence(1L);
        Global.uncacheSessionIds();
        assertEquals(highest.id + 1, (long) createInstance("after", experiment).id);

        // Stopped between dropping the sequence and making it again: the next start makes it.
        Ebean.createSqlUpdate("drop sequence experiment_instances_seq").execute();
        assertNull(sequence());
        Global.uncacheSessionIds();
        assertEquals(Long.valueOf(1L), cache());
        assertEquals(highest.id + 2, (long) createInstance("again", experiment).id);
    }
}
