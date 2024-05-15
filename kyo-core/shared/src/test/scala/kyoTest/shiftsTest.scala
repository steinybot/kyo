package kyoTest

package kyoTest

import kyo.*

class ShiftsTest extends KyoTest:

    "run with a single shift" in {
        assert(
            Shifts.run(Shifts[Int, Int, Any](k => k(42))).pure == 42
        )
    }

    "run with multiple shifts" in {
        assert(
            Shifts.run(
                Shifts[Int, Int, Any](k1 =>
                    Shifts[Int, Int, Any](k2 => k2(42).map(k1))
                )
            ).pure == 42
        )
    }

    "apply with a single shift" in {
        assert(
            Shifts.run(
                Shifts[Int, Int, Any](k => k(42).map(_ + 1))
            ).pure == 43
        )
    }

end ShiftsTest
