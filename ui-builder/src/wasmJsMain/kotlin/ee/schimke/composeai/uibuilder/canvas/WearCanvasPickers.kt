package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.DatePicker
import androidx.wear.compose.material3.DatePickerType
import androidx.wear.compose.material3.TimePicker
import androidx.wear.compose.material3.TimePickerType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** See the `expect` in `WearCanvasComponents` for why these two are per-target. */
@Composable
internal actual fun WearCanvasDatePicker(initialDate: String, type: String, modifier: Modifier) {
  DatePicker(
    initialDate = runCatching { LocalDate.parse(initialDate) }.getOrElse { LocalDate(2026, 1, 1) },
    onDatePicked = {},
    modifier = modifier,
    datePickerType =
      when (type) {
        "day-month-year" -> DatePickerType.DayMonthYear
        "month-day-year" -> DatePickerType.MonthDayYear
        else -> DatePickerType.YearMonthDay
      },
  )
}

@Composable
internal actual fun WearCanvasTimePicker(initialTime: String, type: String, modifier: Modifier) {
  TimePicker(
    initialTime = runCatching { LocalTime.parse(initialTime) }.getOrElse { LocalTime(10, 10) },
    onTimePicked = {},
    modifier = modifier,
    timePickerType =
      when (type) {
        "hours-minutes-am-pm" -> TimePickerType.HoursMinutesAmPm12H
        "hours-minutes-seconds" -> TimePickerType.HoursMinutesSeconds24H
        else -> TimePickerType.HoursMinutes24H
      },
  )
}
