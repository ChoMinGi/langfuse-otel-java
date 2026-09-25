package operations;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimingGuardTest {
    @Test void uninterruptedTwoHourMeasurementIsValid() {
        assertDoesNotThrow(() -> TimingGuard.validate(7200123, 7200000));
    }

    @Test void suspendedInitialRunIsRejected() {
        assertThrows(IllegalStateException.class, () -> TimingGuard.validate(32943043, 294747));
    }

    @Test void backwardClockAndDivergenceInEitherDirectionAreRejected() {
        assertThrows(IllegalStateException.class, () -> TimingGuard.validate(-1, 1000));
        assertThrows(IllegalStateException.class, () -> TimingGuard.validate(1000, 4000));
        assertThrows(IllegalStateException.class, () -> TimingGuard.validate(4000, 1000));
    }
}
