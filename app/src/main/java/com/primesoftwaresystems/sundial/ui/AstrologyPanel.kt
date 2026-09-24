package com.primesoftwaresystems.sundial.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.primesoftwaresystems.sundial.astronomy.Zodiac
import java.time.LocalDate
import java.time.LocalTime

/**
 * The lower-right tuck menu: every astrology input in one place. Birth date and time are typed
 * straight into the panel and saved as soon as they are valid, so the horoscope can be written
 * without any further step.
 */
class AstrologyPanel(context: Context) : LinearLayout(context) {
    private val controls = InstrumentControls(context)
    private val zodiacSwitch = controls.switch("ASTROLOGY MODE")
    private val monthField = numberField("MM", 2, "Birth month")
    private val dayField = numberField("DD", 2, "Birth day")
    private val yearField = numberField("YYYY", 4, "Four digit birth year")
    private val hourField = numberField("HH", 2, "Birth hour")
    private val minuteField = numberField("MM", 2, "Birth minutes")
    private val amButton = meridiem("AM")
    private val pmButton = meridiem("PM")
    private val validation = controls.label("", 13f, 0xFFFF8F7A.toInt()).apply {
        setPadding(controls.dp(2), controls.dp(4), controls.dp(2), 0)
    }
    private val signAction = controls.action("SUN SIGN", "Choose zodiac sign") { onZodiacSignRequested?.invoke() }
    private val horoscopeAction = controls.action("WRITE A NEW READING", "Write today's private horoscope with Gemini Nano") {
        onHoroscopeRequested?.invoke()
    }
    private val status = controls.label("Birth details stay on this device.", 12f, 0x99FFFFFF.toInt()).apply {
        setPadding(controls.dp(4), controls.dp(6), controls.dp(4), controls.dp(4))
    }
    private var pm = false
    private var syncing = false
    private var profile = ZodiacProfile()

    var onZodiacChanged: ((Boolean) -> Unit)? = null
    var onBirthDateChanged: ((LocalDate) -> Unit)? = null
    var onBirthTimeChanged: ((LocalTime) -> Unit)? = null
    var onZodiacSignRequested: (() -> Unit)? = null
    var onHoroscopeRequested: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(controls.dp(22), controls.dp(20), controls.dp(18), controls.dp(16))
        controls.title("ASTROLOGY", "OPTIONAL ZODIAC · PRIVATE ON DEVICE").forEach(::addView)
        addView(zodiacSwitch)
        addView(controls.label(Zodiac.Sign.entries.joinToString(" ") { it.symbol }, 18f, 0xFFFFD88A.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, controls.dp(2), 0, controls.dp(4))
        })

        addView(controls.section("BIRTH DATE"))
        addView(row(monthField to 1f, dayField to 1f, yearField to 1.7f))
        addView(captions("MONTH" to 1f, "DAY" to 1f, "YEAR" to 1.7f))
        addView(controls.section("BIRTH TIME"))
        addView(row(hourField to 1f, colon() to .28f, minuteField to 1f, amButton to .9f, pmButton to .9f))
        addView(validation)
        addView(controls.section("SUN SIGN"))
        addView(signAction)
        addView(controls.section("TODAY'S HOROSCOPE"))
        addView(horoscopeAction)
        addView(status)

        zodiacSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onZodiacChanged?.invoke(checked) }
        advance(monthField, dayField)
        advance(dayField, yearField)
        advance(yearField, hourField)
        advance(hourField, minuteField)
        listOf(monthField, dayField, yearField).forEach { it.afterChange(::commitDate) }
        listOf(hourField, minuteField).forEach { it.afterChange(::commitTime) }
        minuteField.imeOptions = EditorInfo.IME_ACTION_DONE
        amButton.setOnClickListener { setMeridiem(false); commitTime() }
        pmButton.setOnClickListener { setMeridiem(true); commitTime() }
        setMeridiem(false)
    }

    fun setZodiacProfile(value: ZodiacProfile) {
        profile = value
        syncing = true
        zodiacSwitch.isChecked = value.enabled
        value.birthDate?.let { date ->
            monthField.setIfIdle(date.monthValue.toString().padStart(2, '0'))
            dayField.setIfIdle(date.dayOfMonth.toString().padStart(2, '0'))
            yearField.setIfIdle(date.year.toString())
        }
        value.birthTime?.let { time ->
            hourField.setIfIdle((if (time.hour % 12 == 0) 12 else time.hour % 12).toString())
            minuteField.setIfIdle(time.minute.toString().padStart(2, '0'))
            setMeridiem(time.hour >= 12)
        }
        syncing = false
        val sign = value.resolvedSign()
        signAction.text = "${sign.symbol}  ${sign.displayName.uppercase()}" +
            if (value.selectedSign == null || value.birthDate?.let(Zodiac::signFor) == value.selectedSign) "  · FROM BIRTHDAY" else ""
        horoscopeAction.isEnabled = value.isComplete
        horoscopeAction.alpha = if (value.isComplete) 1f else .46f
    }

    fun setHoroscopeStatus(message: String) {
        status.text = message
    }

    private fun commitDate() {
        if (syncing) return
        val parts = listOf(monthField, dayField, yearField).map { it.text.toString() }
        if (parts.any { it.isBlank() } || parts[2].length < 4) return showValidation(null)
        runCatching { BirthDateInput.parse(parts[0], parts[1], parts[2]) }
            .onSuccess { date ->
                showValidation(null)
                if (date != profile.birthDate) onBirthDateChanged?.invoke(date)
            }
            .onFailure { showValidation(it.message) }
    }

    private fun commitTime() {
        if (syncing) return
        val hour = hourField.text.toString()
        val minute = minuteField.text.toString()
        if (hour.isBlank() || minute.isBlank()) return showValidation(null)
        runCatching { BirthTimeInput.parse(hour, minute, pm) }
            .onSuccess { time ->
                showValidation(null)
                if (time != profile.birthTime) onBirthTimeChanged?.invoke(time)
            }
            .onFailure { showValidation(it.message) }
    }

    private fun showValidation(message: String?) {
        validation.text = message.orEmpty()
        validation.visibility = if (message.isNullOrBlank()) GONE else VISIBLE
    }

    private fun setMeridiem(value: Boolean) {
        pm = value
        listOf(amButton to !value, pmButton to value).forEach { (button, selected) ->
            button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            button.background = GradientDrawable().apply {
                cornerRadius = controls.dp(12).toFloat()
                setColor(if (selected) InstrumentControls.BRASS else 0x12000000)
                setStroke(controls.dp(1), if (selected) InstrumentControls.BRASS else 0x45FFFFFF)
            }
        }
    }

    private fun numberField(hint: String, digits: Int, description: String) = EditText(context).apply {
        this.hint = hint
        contentDescription = description
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(digits))
        maxLines = 1
        gravity = Gravity.CENTER
        textSize = 20f
        typeface = controls.typeface
        setTextColor(Color.WHITE)
        setHintTextColor(0x55FFFFFF)
        setSelectAllOnFocus(true)
        imeOptions = EditorInfo.IME_ACTION_NEXT
        background = GradientDrawable().apply {
            cornerRadius = controls.dp(12).toFloat()
            setColor(0x14FFFFFF)
            setStroke(controls.dp(1), 0x45FFFFFF)
        }
        setPadding(0, 0, 0, 0)
    }

    private fun meridiem(label: String) = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = controls.typeface
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = "Birth time is $label"
    }

    private fun colon() = controls.label(":", 22f, Color.WHITE).apply { gravity = Gravity.CENTER }

    private fun row(vararg cells: Pair<android.view.View, Float>) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        cells.forEachIndexed { index, (view, weight) ->
            addView(view, LayoutParams(0, controls.dp(52), weight).apply {
                if (index < cells.lastIndex) marginEnd = controls.dp(6)
            })
        }
    }

    private fun captions(vararg cells: Pair<String, Float>) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        cells.forEachIndexed { index, (text, weight) ->
            addView(controls.label(text, 10f, 0x88FFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                letterSpacing = .12f
            }, LayoutParams(0, controls.dp(22), weight).apply {
                if (index < cells.lastIndex) marginEnd = controls.dp(6)
            })
        }
    }

    /** Moves on to [next] once [field] holds all of its digits. */
    private fun advance(field: EditText, next: EditText) {
        field.afterChange {
            val max = (field.filters.firstOrNull() as? InputFilter.LengthFilter)?.max ?: return@afterChange
            if (!syncing && field.hasFocus() && field.text.length >= max) next.requestFocus()
        }
    }

    private fun EditText.afterChange(action: () -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = action()
    })

    /** Updates a field from the saved profile unless the person is typing in it. */
    private fun EditText.setIfIdle(value: String) {
        if (!hasFocus() && text.toString() != value) setText(value)
    }
}
