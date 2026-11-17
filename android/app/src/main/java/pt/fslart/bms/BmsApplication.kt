package pt.fslart.bms

import android.app.Application
import pt.fslart.bms.session.BmsSession

class BmsApplication : Application() {

    /** Ligacao e telemetria ao nivel do processo (sobrevive ao ecra fechado). */
    val session: BmsSession by lazy { BmsSession(this) }
}
