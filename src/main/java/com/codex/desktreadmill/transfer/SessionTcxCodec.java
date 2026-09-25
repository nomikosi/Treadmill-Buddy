package com.codex.desktreadmill.transfer;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SpeedSegment;

import java.time.Instant;
import java.util.Locale;

/** TCX conversion without UI or file-system access. */
public final class SessionTcxCodec {
    private SessionTcxCodec() {
    }

    /**
     * The TCX document for one session. The sport is {@code Other}: the TCX v2
     * schema only allows Running, Biking, and Other, and a made-up value such
     * as "Walking" fails validation in strict importers. Services show it as a
     * generic workout that can be relabelled as a walk after import.
     */
    public static String buildTcx(SessionData session) {
        Instant start = Instant.ofEpochMilli(session.createdMillis);
        // String.format with Locale.ROOT, not "...".formatted(...): the latter
        // uses the default locale, and %d renders Eastern Arabic digits under
        // ar/fa/bn, which no fitness service will parse as XML numbers.
        return String.format(Locale.ROOT, """
                <?xml version="1.0" encoding="UTF-8"?>
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
                  <Activities>
                    <Activity Sport="Other">
                      <Id>%s</Id>
                      <Lap StartTime="%s">
                        <TotalTimeSeconds>%d</TotalTimeSeconds>
                        <DistanceMeters>%.1f</DistanceMeters>
                        <Calories>%d</Calories>
                        <Intensity>Active</Intensity>
                        <TriggerMethod>Manual</TriggerMethod>
                        <Track>
                %s        </Track>
                      </Lap>
                      <Notes>%s</Notes>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """,
                start, start,
                session.elapsedSeconds,
                session.distanceKm * 1000.0,
                Math.max(0, Math.round(session.calories)),
                buildTrackpoints(session, start),
                xmlEscape(session.name));
    }

    /**
     * One trackpoint per minute plus the final second. Some services (notably
     * Strava) reject TCX laps without a track. Distances follow the recorded
     * speed segments when present, scaled so the last point lands exactly on
     * the session total; sessions without segments interpolate linearly.
     */
    static String buildTrackpoints(SessionData session, Instant start) {
        StringBuilder track = new StringBuilder();
        long elapsed = session.elapsedSeconds;
        double totalMeters = session.distanceKm * 1000.0;
        double segmentTotalMeters = 0.0;
        long segmentTotalSeconds = 0L;
        for (SpeedSegment segment : session.segments) {
            segmentTotalMeters += segment.speedKmh / 3.6 * segment.seconds;
            segmentTotalSeconds += segment.seconds;
        }
        // CSV/legacy sessions can have segments for only the activity since
        // resuming. Those cannot describe the beginning of the full workout.
        boolean useSegments = segmentTotalMeters > 0 && segmentTotalSeconds > 0
                && segmentTotalSeconds == elapsed;
        double scale = useSegments ? totalMeters / segmentTotalMeters : 1.0;
        for (long t = 0; ; t += 60) {
            boolean last = t >= elapsed;
            long at = last ? elapsed : t;
            double meters;
            if (last) {
                meters = totalMeters;
            } else if (useSegments) {
                meters = distanceFromSegments(session, at) * scale;
            } else {
                meters = elapsed > 0 ? totalMeters * at / elapsed : 0.0;
            }
            track.append(String.format(Locale.ROOT,
                    "          <Trackpoint><Time>%s</Time><DistanceMeters>%.1f</DistanceMeters></Trackpoint>%n",
                    start.plusSeconds(at), meters));
            if (last) {
                return track.toString();
            }
        }
    }

    private static double distanceFromSegments(SessionData session, long atSeconds) {
        double meters = 0.0;
        long consumed = 0L;
        for (SpeedSegment segment : session.segments) {
            long inSegment = Math.min(segment.seconds, atSeconds - consumed);
            if (inSegment <= 0) {
                break;
            }
            meters += segment.speedKmh / 3.6 * inSegment;
            consumed += inSegment;
        }
        return meters;
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
