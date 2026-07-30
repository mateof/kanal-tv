package com.mateof.kanal.core

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A message a view model wants to show, named rather than written out.
 *
 * View models cannot read resources in the language the interface settled on —
 * that choice lives in the composition — so they name the string and hand over
 * the arguments, and the screen turns it into words.
 */
data class UiText(
    @StringRes val res: Int,
    val args: List<Any> = emptyList()
) {
    constructor(@StringRes res: Int, vararg args: Any) : this(res, args.toList())
}

/** An argument that has to agree in number with what it counts. */
data class Plural(@PluralsRes val res: Int, val count: Int)

@Composable
fun UiText.resolve(): String {
    if (args.isEmpty()) return stringResource(res)
    val resolved = args.map { arg ->
        when (arg) {
            is Plural -> pluralStringResource(arg.res, arg.count, arg.count)
            else -> arg
        }
    }
    return stringResource(res, *resolved.toTypedArray())
}

@Composable
fun UiText?.resolveOrEmpty(): String = this?.resolve() ?: ""
