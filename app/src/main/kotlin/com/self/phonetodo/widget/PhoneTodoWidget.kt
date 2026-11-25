package com.self.phonetodo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.self.phonetodo.MainActivity
import com.self.phonetodo.PhoneTodoApp
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.reminder.ReminderCalculator

private data class WidgetTodoRow(
    val title: String,
    val isUrgentRed: Boolean,
)

class PhoneTodoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as PhoneTodoApp
        val now = System.currentTimeMillis()
        val overview = app.usageRepository.refresh(now)
        val allPending = ReminderCalculator.sortPendingTodos(app.todoRepository.data.value.activeTodos().filter { !it.done })
        val todayTodos = allPending.filter { ReminderCalculator.isTodayTodo(it, now) }
        val rows = todayTodos.take(4).map { t ->
            val urgent = ReminderCalculator.isPastCarryover(t, now) ||
                (t.reminder != null && ReminderCalculator.isFiredOrOverdue(t.reminder, now))
            WidgetTodoRow(title = t.text, isUrgentRed = urgent)
        }

        provideContent {
            WidgetContent(
                unlocks = overview.todayUnlocks,
                screenTime = UsageRepository.formatDurationShort(overview.todayScreenMillis),
                todayCount = todayTodos.size,
                rows = rows,
            )
        }
    }

    @Composable
    private fun WidgetContent(
        unlocks: Int,
        screenTime: String,
        todayCount: Int,
        rows: List<WidgetTodoRow>,
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(22.dp)
                .background(Color(0xEB0F1316))
                .clickable(actionStartActivity<MainActivity>())
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (todayCount > 0) "今日 $todayCount" else "今日",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFEEF2F1)),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "${unlocks}次  $screenTime",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF3DB59E)),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            if (rows.isEmpty()) {
                Text(
                    text = "今日待办已清空",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF818C91)),
                        fontSize = 13.sp,
                    ),
                )
            } else {
                rows.forEach { row ->
                    Text(
                        text = if (row.isUrgentRed) "● ${row.title}" else row.title,
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(if (row.isUrgentRed) Color(0xFFF06C6C) else Color(0xFFEEF2F1)),
                            fontSize = 13.sp,
                        ),
                        modifier = GlanceModifier.padding(vertical = 2.dp),
                    )
                }
                if (todayCount > rows.size) {
                    Text(
                        text = "其余 ${todayCount - rows.size} 项",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF818C91)),
                            fontSize = 11.sp,
                        ),
                        modifier = GlanceModifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

class PhoneTodoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PhoneTodoWidget()
}
