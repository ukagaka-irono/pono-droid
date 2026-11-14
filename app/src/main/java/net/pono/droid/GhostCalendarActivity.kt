package net.pono.droid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.CalendarEntry
import net.pono.droid.overlay.GhostOverlayService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** ゴーストの予定・購読・許可済みの端末の予定を日付ごとに表示する。 */
class GhostCalendarActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ghost = intent.getStringExtra("ghost").orEmpty()
        setContent {
            MaterialTheme {
                var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
                val day = LocalDate.parse(dateText)
                var choosingDate by rememberSaveable { mutableStateOf(false) }
                val running by GhostOverlayService.runningGhosts.collectAsState()
                val controller = if (ghost in running) GhostOverlayService.findController(ghost) else null
                var entries by remember { mutableStateOf<List<CalendarEntry>>(emptyList()) }
                var loading by remember { mutableStateOf(true) }
                LaunchedEffect(controller, day) {
                    entries = emptyList()
                    loading = controller != null
                    if (controller == null) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (isActive) {
                            entries = withContext(Dispatchers.IO) { controller.calendarEntries(day) }
                            loading = false
                            delay(60_000)
                        }
                    }
                }
                Scaffold(topBar = {
                    TopAppBar(title = { Text(stringResource(R.string.calendar_title)) }, actions = {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.viewer_close)) }
                    })
                }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Text(controller?.ghost?.name ?: stringResource(R.string.input_stopped)) }
                        item {
                            TextButton(onClick = { choosingDate = true }) {
                                Text(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { dateText = day.minusDays(1).toString() }) { Text(stringResource(R.string.calendar_previous)) }
                                TextButton(onClick = { dateText = LocalDate.now().toString() }) { Text(stringResource(R.string.calendar_today)) }
                                TextButton(onClick = { dateText = day.plusDays(1).toString() }) { Text(stringResource(R.string.calendar_next)) }
                            }
                            Text(stringResource(R.string.calendar_help), style = MaterialTheme.typography.bodySmall)
                        }
                        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (!loading && entries.isEmpty()) item { Text(stringResource(R.string.calendar_empty)) }
                        items(entries) { entry ->
                            Card(Modifier.fillMaxWidth().combinedClickable(
                                enabled = controller != null,
                                onClick = { controller?.onScheduleRead(entry) },
                                onLongClick = { controller?.onScheduleRead(entry) },
                            )) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    fun time(moment: CalendarEntry.Moment): String {
                                        val time = moment.time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)).orEmpty()
                                        return if (moment.date == day) time else moment.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)) + " " + time
                                    }
                                    val start = if (entry.start.time == null) stringResource(R.string.calendar_all_day) else time(entry.start)
                                    Text(start + (entry.end?.let { " – " + time(it) }.orEmpty()), style = MaterialTheme.typography.labelLarge)
                                    Text(entry.caption, style = MaterialTheme.typography.titleMedium)
                                    if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                if (choosingDate) {
                    val picker = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                    DatePickerDialog(
                        onDismissRequest = { choosingDate = false },
                        confirmButton = {
                            TextButton(onClick = {
                                picker.selectedDateMillis?.let { dateText = Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() }
                                choosingDate = false
                            }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.calendar_choose)) }
                        },
                        dismissButton = { TextButton(onClick = { choosingDate = false }) { Text(stringResource(R.string.viewer_close)) } },
                    ) { DatePicker(picker) }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); GhostOverlayService.setAppInFront(true) }
    override fun onPause() { GhostOverlayService.setAppInFront(false); super.onPause() }

    companion object {
        fun start(context: Context, ghost: String) = context.startActivity(Intent(context, GhostCalendarActivity::class.java)
            .putExtra("ghost", ghost).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
