package com.vigasboiler.app.feature

import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.round
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

data class ValidationError(val param: String, val kind: Kind, val detail: String = "") {
    enum class Kind {
        Missing, UnknownParam, WrongType, BelowMin, AboveMax, OffStep, NotInEnum,
        PatternMismatch, TooShort, TooLong, EndBeforeStart, InvalidSchedule,
    }
}

/**
 * Client-side validation of a command body against the `params` constraints from the API, so
 * invalid values are caught before a rate-limited call is spent on them.
 */
object CommandValidator {

    fun validate(command: Command, values: Map<String, JsonElement>): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        values.keys.filter { it !in command.params }.forEach { errors += ValidationError(it, ValidationError.Kind.UnknownParam) }
        command.params.values.forEach { param ->
            val value = values[param.name]
            if (value == null) {
                if (param.required) errors += ValidationError(param.name, ValidationError.Kind.Missing)
            } else {
                errors += validateParam(param, value)
            }
        }
        errors += validateDateRange(command, values)
        return errors
    }

    /** The JSON body for the command. Call only after [validate] returned no errors. */
    fun body(command: Command, values: Map<String, JsonElement>): JsonObject =
        JsonObject(values.filterKeys { it in command.params })

    fun validateParam(param: CommandParam, value: JsonElement): List<ValidationError> {
        val c = param.constraints
        val p = param.name
        val primitive = value as? JsonPrimitive
        return when (param.type) {
            "number" -> {
                val v = primitive?.takeIf { !it.isString }?.doubleOrNull
                    ?: return listOf(ValidationError(p, ValidationError.Kind.WrongType, "number"))
                buildList {
                    c.min?.let { if (v < it - EPS) add(ValidationError(p, ValidationError.Kind.BelowMin, fmt(it))) }
                    c.max?.let { if (v > it + EPS) add(ValidationError(p, ValidationError.Kind.AboveMax, fmt(it))) }
                    c.stepping?.takeIf { it > 0 }?.let { step ->
                        val n = (v - (c.min ?: 0.0)) / step
                        if (abs(n - round(n)) > 1e-6) add(ValidationError(p, ValidationError.Kind.OffStep, fmt(step)))
                    }
                }
            }
            "string" -> {
                val v = primitive?.takeIf { it.isString }?.content
                    ?: return listOf(ValidationError(p, ValidationError.Kind.WrongType, "string"))
                buildList {
                    c.enum?.let { if (v !in it) add(ValidationError(p, ValidationError.Kind.NotInEnum, it.joinToString())) }
                    c.regEx?.let { pattern ->
                        val matches = runCatching { Regex(pattern).containsMatchIn(v) }.getOrDefault(true)
                        if (!matches) add(ValidationError(p, ValidationError.Kind.PatternMismatch, pattern))
                    }
                    c.minLength?.let { if (v.length < it) add(ValidationError(p, ValidationError.Kind.TooShort, "$it")) }
                    c.maxLength?.let { if (v.length > it) add(ValidationError(p, ValidationError.Kind.TooLong, "$it")) }
                }
            }
            "boolean" ->
                if (primitive?.takeIf { !it.isString }?.booleanOrNull == null) listOf(ValidationError(p, ValidationError.Kind.WrongType, "boolean"))
                else emptyList()
            "Schedule" -> {
                val schedule = Schedule.fromJson(value)
                    ?: return listOf(ValidationError(p, ValidationError.Kind.WrongType, "Schedule"))
                ScheduleRules.from(c).validate(schedule).map {
                    ValidationError(p, ValidationError.Kind.InvalidSchedule, "${it.day.key}: ${it.kind} ${it.detail}".trim())
                }
            }
            else -> emptyList() // Unknown types are sent as entered; the API has the final word.
        }
    }

    /** Holiday programs: `end` must not be before `start`, and not equal unless `sameDayAllowed`. */
    private fun validateDateRange(command: Command, values: Map<String, JsonElement>): List<ValidationError> {
        val endParam = command.params["end"] ?: return emptyList()
        if (command.params["start"] == null) return emptyList()
        val start = values["start"].date() ?: return emptyList()
        val end = values["end"].date() ?: return emptyList()
        val sameDay = endParam.constraints.sameDayAllowed ?: true
        return if (end.isBefore(start) || (!sameDay && end == start)) {
            listOf(ValidationError("end", ValidationError.Kind.EndBeforeStart))
        } else {
            emptyList()
        }
    }

    private fun JsonElement?.date(): LocalDate? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun fmt(d: Double) = if (d == round(d)) d.toLong().toString() else d.toString()
    private const val EPS = 1e-9
}
