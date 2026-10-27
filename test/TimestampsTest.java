import models.Timestamps;
import org.junit.Test;

import java.util.Date;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class TimestampsTest {

    @Test
    public void csvTimestampsAreUtcWhateverTheServerTimeZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            // 1970-01-01T08:00 in Shanghai is midnight UTC.
            assertEquals("1970-01-01T00:00:00:000Z", Timestamps.csv(new Date(0L)));
            assertEquals("2026-10-26T10:23:37:250Z", Timestamps.csv(new Date(1793010217250L)));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
