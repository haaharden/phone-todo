package com.self.phonetodo.reminder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.self.phonetodo.PhoneTodoApp
import com.self.phonetodo.model.ThemeMode
import com.self.phonetodo.ui.theme.DialAmberDark
import com.self.phonetodo.ui.theme.ObsidianGround
import com.self.phonetodo.ui.theme.ObsidianSurface
import com.self.phonetodo.ui.theme.PhoneToDoTheme
import com.self.phonetodo.ui.theme.PineAccentDark

class StrongAlertActivity : ComponentActivity() {

    private var todoIdState by mutableStateOf("")
    private var todoTextState by mutableStateOf("")
    private var notifIdState by mutableIntStateOf(-1)
    private var isRecurringState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyAlertIntent(intent)

        setContent {
            PhoneToDoTheme(themeMode = ThemeMode.DARK) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ObsidianGround)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = ObsidianSurface,
                        border = BorderStroke(0.5.dp, Color(0xFF283238)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(DialAmberDark.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Alarm,
                                    contentDescription = null,
                                    tint = DialAmberDark,
                                    modifier = Modifier.size(34.dp),
                                )
                            }

                            Text(
                                text = "重要待办强提醒",
                                style = MaterialTheme.typography.labelLarge,
                                color = DialAmberDark,
                            )

                            Text(
                                text = todoTextState.ifBlank { "待办事项已到提醒时间" },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFEEF2F1),
                                textAlign = TextAlign.Center,
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val currentId = todoIdState
                                        val currentNotifId = notifIdState
                                        StrongAlertAudioController.stop(currentId)
                                        StrongAlertOverlayController.dismiss(currentId)
                                        val app = application as PhoneTodoApp
                                        if (currentId.isNotBlank() && !currentId.startsWith("test_reminder_")) {
                                            app.todoRepository.snoozeReminder(currentId, 10L)
                                        }
                                        if (currentNotifId > 0) {
                                            NotificationHelper.cancelReminderNotification(this@StrongAlertActivity, currentNotifId)
                                        }
                                        finish()
                                    },
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Icon(Icons.Rounded.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(" 推迟10分")
                                }

                                Button(
                                    onClick = {
                                        val currentId = todoIdState
                                        val currentNotifId = notifIdState
                                        StrongAlertAudioController.stop(currentId)
                                        StrongAlertOverlayController.dismiss(currentId)
                                        val app = application as PhoneTodoApp
                                        if (currentId.isNotBlank() && !currentId.startsWith("test_reminder_")) {
                                            val existing = app.todoRepository.data.value.activeTodos().firstOrNull { it.id == currentId }
                                            if (existing != null && !existing.done) {
                                                app.todoRepository.toggleTodoDone(currentId)
                                            }
                                        }
                                        if (currentNotifId > 0) {
                                            NotificationHelper.cancelReminderNotification(this@StrongAlertActivity, currentNotifId)
                                        }
                                        finish()
                                    },
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PineAccentDark,
                                        contentColor = Color(0xFF07231E),
                                    ),
                                ) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(if (isRecurringState) " 完成本次" else " 已完成", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            TextButton(
                                onClick = {
                                    val currentId = todoIdState
                                    val currentNotifId = notifIdState
                                    StrongAlertAudioController.stop(currentId)
                                    StrongAlertOverlayController.dismiss(currentId)
                                    if (currentNotifId > 0) {
                                        NotificationHelper.cancelReminderNotification(this@StrongAlertActivity, currentNotifId)
                                    }
                                    finish()
                                },
                            ) {
                                Text("先关闭响铃", color = Color(0xFF818C91))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyAlertIntent(intent)
    }

    private fun applyAlertIntent(intent: Intent) {
        todoIdState = intent.getStringExtra(ReminderReceiver.EXTRA_TODO_ID).orEmpty()
        todoTextState = intent.getStringExtra(ReminderReceiver.EXTRA_TODO_TEXT).orEmpty()
        notifIdState = intent.getIntExtra(ReminderReceiver.EXTRA_NOTIF_ID, -1)
        isRecurringState = intent.getBooleanExtra(ReminderReceiver.EXTRA_IS_RECURRING, false)
        if (todoIdState.isNotBlank()) {
            StrongAlertOverlayController.dismiss(todoIdState)
            StrongAlertAudioController.start(this, todoIdState)
        }
    }

    override fun onResume() {
        super.onResume()
        visibleTodoId = todoIdState.takeIf { it.isNotBlank() }
        if (todoIdState.isNotBlank()) {
            StrongAlertOverlayController.dismiss(todoIdState)
        }
    }

    override fun onPause() {
        if (visibleTodoId == todoIdState) {
            visibleTodoId = null
        }
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            StrongAlertAudioController.stop(todoIdState.takeIf { it.isNotBlank() })
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!isChangingConfigurations && (isFinishing || pm.isInteractive)) {
            StrongAlertAudioController.stop(todoIdState.takeIf { it.isNotBlank() })
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (visibleTodoId == todoIdState) {
            visibleTodoId = null
        }
        if (!isChangingConfigurations) {
            StrongAlertAudioController.stop(todoIdState.takeIf { it.isNotBlank() })
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var visibleTodoId: String? = null

        fun isVisibleFor(todoId: String): Boolean = visibleTodoId == todoId
    }
}
