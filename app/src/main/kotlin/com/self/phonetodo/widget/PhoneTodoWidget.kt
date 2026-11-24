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

class PhoneTodoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as PhoneTodoApp
        val overview = app.usageRepository.refresh()
        val topTodos = app.todoRepository.topBannerTodos(3)
        val pendingCount = app.todoRepository.pendingCount()

        provideContent {
            WidgetContent(
                unlocks = overview.todayUnlocks,
                screenTime = UsageRepository.formatDurationShort(overview.todayScreenMillis),
                pendingCount = pendingCount,
                todoTitles = topTodos.map { it.text },
            )
        }
    }

    @Composable
    private fun WidgetContent(
        unlocks: Int,
        screenTime: String,
        pendingCount: Int,
        todoTitles: List<String>,
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
                    text = "Phone ToDo",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFEEF2F1)),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "${unlocks}次解锁  $screenTime",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF3DB59E)),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            if (todoTitles.isEmpty()) {
                Text(
                    text = "今日待办已清空",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF818C91)),
                        fontSize = 13.sp,
                    ),
                )
            } else {
                todoTitles.forEach { line ->
                    Text(
                        text = line,
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFEEF2F1)),
                            fontSize = 13.sp,
                        ),
                        modifier = GlanceModifier.padding(vertical = 2.dp),
                    )
                }
                if (pendingCount > todoTitles.size) {
                    Text(
                        text = "其余 ${pendingCount - todoTitles.size} 项",
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
