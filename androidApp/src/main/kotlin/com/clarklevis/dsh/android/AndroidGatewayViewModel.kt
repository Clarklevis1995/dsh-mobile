package com.clarklevis.dsh.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Activity ViewModel 保留 UI/MVI 订阅；socket 本身由 Application graph 单例持有。 */
class AndroidGatewayViewModel(application: Application) : AndroidViewModel(application) {
    /** Holder/Projection 属于 Application graph；Activity 真正 finish/reopen 仍保留 baseline。 */
    val stateHolder = (application as DshAndroidApplication).graph.stateHolder
}
