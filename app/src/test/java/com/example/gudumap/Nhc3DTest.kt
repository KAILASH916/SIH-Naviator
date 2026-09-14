package com.example.gudumap

import com.example.gudumap.navigation.EKF
import com.example.gudumap.navigation.NHC
import org.junit.Assert.assertTrue
import org.junit.Test

class Nhc3DTest {

    @Test
    fun test3DNhcSupportsPitchAndRollAttitude() {
        val ekf = EKF()
        // Heading 0 deg (North), Pitch 15 deg (nose up), Roll 10 deg
        ekf.initialize(pNorth = 0.0, pEast = 0.0, pDown = 0.0, vNorth = 10.0, vEast = 2.0, vDown = 1.0)

        val nhc = NHC(isEnabled = true, lateralNoiseMps = 0.1, verticalNoiseMps = 0.1)
        nhc.applyConstraint(ekf, headingDeg = 0f, pitchDeg = 15f, rollDeg = 10f)

        // NHC should adjust state without throwing matrix errors
        assertTrue("Forward velocity component should be preserved", ekf.state[3] > 8.0)
    }
}
