package com.leohearts.alternativeUnlockHook

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.withTimeoutOrNull

// ---- Command editor ----
// Hand-rolled on BasicTextField on purpose: it keeps the platform IME path, which is the
// part third-party editor libraries get wrong on Android. Highlighting is a small
// single-pass scanner; the line-number gutter is derived from the field's own layout
// result, so it stays aligned even with soft wrap on.

// Compose text layout has no tab-stop support and renders '\t' one space wide, so the
// editor works in spaces. Existing tabs are expanded on load.
private const val TAB = "    "

private fun expandTabs(text: String) = text.replace("\t", TAB)

private val bashKeywords = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
    "case", "esac", "in", "function", "return", "exit", "export", "local", "source"
)

// Single-pass bash highlighter. Rules verified against real bash(1) behavior:
// - '#' starts a comment only at a word boundary (POSIX: "a word beginning with #"),
//   i.e. after whitespace or a control/redirect operator. `echo a#b` prints a#b;
//   `echo hi >#f` is a syntax error because ># is operator + comment. `//` is never
//   a comment.
// - strings: single, double (\" escape) and backticks (opaque span; a # inside a
//   backtick substitution is technically a comment, we color it as the substitution).
// - variables: $VAR / ${VAR} / $? style. ${...} is consumed as one span, so the '#'
//   in ${x#pattern} is never mistaken for a comment.
// - heredocs (<<[-]DELIM, not <<<): body up to the delimiter line is literal text,
//   colored as a string. `<<` inside $( ) is arithmetic shift, not a heredoc.
fun highlightBash(code: String, keyword: Color, string: Color, comment: Color, variable: Color): AnnotatedString {
    val builder = AnnotatedString.Builder(code)
    val n = code.length
    var i = 0
    var parenDepth = 0 // tracks $( ... ) nesting
    while (i < n) {
        val c = code[i]
        when {
            // comment: '#' at a word boundary
            c == '#' && (i == 0 || code[i - 1] in " \t\r\n;&|()<>`") -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                builder.addStyle(SpanStyle(color = comment), i, end)
                i = end
            }
            c == '"' -> {
                var j = i + 1
                while (j < n && code[j] != '"') {
                    if (code[j] == '\\') j++
                    j++
                }
                val end = minOf(j + 1, n)
                builder.addStyle(SpanStyle(color = string), i, end)
                i = end
            }
            c == '\'' -> {
                val end = code.indexOf('\'', i + 1).let { if (it < 0) n else it + 1 }
                builder.addStyle(SpanStyle(color = string), i, end)
                i = end
            }
            c == '`' -> {
                var j = i + 1
                while (j < n && code[j] != '`') {
                    if (code[j] == '\\') j++
                    j++
                }
                val end = minOf(j + 1, n)
                builder.addStyle(SpanStyle(color = string), i, end)
                i = end
            }
            c == '$' -> {
                when {
                    i + 1 < n && code[i + 1] == '{' -> {
                        val end = code.indexOf('}', i + 2).let { if (it < 0) n else it + 1 }
                        builder.addStyle(SpanStyle(color = variable), i, end)
                        i = end
                    }
                    i + 1 < n && code[i + 1] == '(' -> {
                        parenDepth++ // command substitution / arithmetic; << inside is not a heredoc
                        i += 2
                    }
                    else -> {
                        var j = i + 1
                        while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        if (j > i + 1) {
                            builder.addStyle(SpanStyle(color = variable), i, j)
                            i = j
                        } else if (j < n && code[j] in "@*#?$!-_0123456789") {
                            builder.addStyle(SpanStyle(color = variable), i, j + 1)
                            i = j + 1
                        } else {
                            i++
                        }
                    }
                }
            }
            // heredoc: <<[-] ['"]DELIM['"] — body runs from the next line until a line
            // whose (tab-stripped) content is DELIM; it is literal text, not code
            c == '<' && parenDepth == 0 && code.getOrNull(i + 1) == '<' && code.getOrNull(i + 2) != '<' -> {
                var j = i + 2
                if (j < n && code[j] == '-') j++
                while (j < n && (code[j] == ' ' || code[j] == '\t')) j++
                if (j < n && (code[j] == '"' || code[j] == '\'')) j++
                if (j < n && code[j] == '\\') j++
                val dstart = j
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val delim = code.substring(dstart, j)
                if (delim.isEmpty()) {
                    i++
                } else {
                    val bodyStart = code.indexOf('\n', i).let { if (it < 0) n else it + 1 }
                    var end = n
                    var p = bodyStart
                    while (p < n) {
                        val lineEnd = code.indexOf('\n', p).let { if (it < 0) n else it }
                        if (code.substring(p, lineEnd).trimStart('\t') == delim) {
                            end = p // the delimiter line itself is not part of the body
                            break
                        }
                        p = lineEnd + 1
                    }
                    if (bodyStart < end) builder.addStyle(SpanStyle(color = string), bodyStart, end)
                    i = end
                }
            }
            c == ')' && parenDepth > 0 -> {
                parenDepth--
                i++
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                if (bashKeywords.contains(code.substring(i, j))) {
                    builder.addStyle(SpanStyle(color = keyword), i, j)
                }
                i = j
            }
            else -> i++
        }
    }
    return builder.toAnnotatedString()
}

@Composable
private fun BashEditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = cs.onSurface
    )
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Gutter: number at each logical line start, blank for wrapped continuation lines.
    // Visual line starts come from the field's own layout result, so numbers stay
    // aligned with soft wrap on; both sides share the same TextStyle (lineHeight).
    val gutter = remember(value.text, layout) {
        val lr = layout ?: return@remember "1"
        buildString {
            for (vline in 0 until lr.lineCount) {
                val start = lr.getLineStart(vline)
                if (start == 0 || value.text.getOrNull(start - 1) == '\n') {
                    append(value.text.substring(0, start).count { it == '\n' } + 1)
                }
                if (vline < lr.lineCount - 1) append('\n')
            }
        }
    }

    BoxWithConstraints(modifier) {
        val minHeight = maxHeight
        Column() {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = minHeight)
                    // taps on the empty area below/beside the text: focus the field
                    // and drop the cursor at the end, like a regular editor
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                        onValueChange(value.copy(selection = TextRange(value.text.length)))
                    }
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    gutter,
                    style = codeStyle.copy(color = cs.outline),
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = 8.dp)
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = codeStyle,
                    cursorBrush = SolidColor(cs.primary),
                    onTextLayout = { layout = it },
                    visualTransformation = {
                        TransformedText(
                            highlightBash(it.text, cs.primary, cs.tertiary, cs.outline, cs.secondary),
                            OffsetMapping.Identity
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )
            }
        }
    }
}

// Accessory keys. Plain Text + pointerInput instead of Button: buttons take focus,
// which would dismiss the IME on every press. Holding a key auto-repeats it, like a
// hardware or IME key would (fire on press, 400ms delay, then every 60ms).
@Composable
private fun accessoryKey(label: String, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Text(
        label,
        modifier = Modifier
            .background(
                if (pressed) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onClick()
                        var delay = 400L
                        while (true) {
                            val released = withTimeoutOrNull(delay) { tryAwaitRelease() }
                            if (released != null) break // released or cancelled
                            onClick()
                            delay = 60L
                        }
                        pressed = false
                    }
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp)
    )
}

private fun moveCursorHorizontally(value: TextFieldValue, dir: Int): TextFieldValue {
    val sel = value.selection
    val target = when {
        !sel.collapsed && dir < 0 -> sel.start
        !sel.collapsed && dir > 0 -> sel.end
        else -> (sel.end + dir).coerceIn(0, value.text.length)
    }
    return value.copy(selection = TextRange(target))
}

private fun moveCursorVertically(value: TextFieldValue, dir: Int): TextFieldValue {
    val text = value.text
    val head = value.selection.end
    val lineStart = text.lastIndexOf('\n', head - 1).let { if (it < 0) 0 else it + 1 }
    val column = head - lineStart
    val target = if (dir < 0) {
        if (lineStart == 0) return value
        val prevEnd = lineStart - 1
        val prevStart = text.lastIndexOf('\n', prevEnd - 1).let { if (it < 0) 0 else it + 1 }
        prevStart + minOf(column, prevEnd - prevStart)
    } else {
        val lineEnd = text.indexOf('\n', head).let { if (it < 0) text.length else it }
        if (lineEnd == text.length) return value
        val nextStart = lineEnd + 1
        val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
        nextStart + minOf(column, nextEnd - nextStart)
    }
    return value.copy(selection = TextRange(target))
}

private fun insertAtCursor(value: TextFieldValue, insert: String): TextFieldValue {
    val sel = value.selection
    val newText = value.text.replaceRange(sel.start, sel.end, insert)
    return TextFieldValue(newText, TextRange(sel.start + insert.length))
}

// When the change is a single '\n' insertion (the IME Enter key), copy the current
// line's leading whitespace onto the new line. Anything else (paste, deletion,
// multi-char commit) passes through untouched.
private fun maybeAutoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val newText = new.text
    if (newText.length != old.text.length + 1) return new
    val pos = new.selection.start
    if (pos == 0 || newText.getOrNull(pos - 1) != '\n') return new
    val lineStart = old.text.lastIndexOf('\n', pos - 2).let { if (it < 0) 0 else it + 1 }
    val indent = old.text.substring(lineStart, pos - 1).takeWhile { it == ' ' || it == '\t' }
    if (indent.isEmpty()) return new
    return TextFieldValue(
        newText.substring(0, pos) + indent + newText.substring(pos),
        TextRange(pos + indent.length)
    )
}

// Full-size variant of the standard edit dialog for the action command: same AlertDialog
// structure (title / content / Confirm / Cancel), content is a bash editor with line
// numbers, soft wrap and an accessory key row.
@Composable
fun CommandEditDialog(
    title: String,
    hint: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val normalized = expandTabs(initial)
        mutableStateOf(TextFieldValue(normalized, TextRange(normalized.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            // make the dialog window resize when the IME opens, so the accessory
            // row and the buttons stay above the keyboard instead of being covered
            val dialogView = LocalView.current
            LaunchedEffect(Unit) {
                (dialogView.parent as? DialogWindowProvider)?.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
            }
            Column {
                BashEditorField(
                    value = value,
                    onValueChange = { value = maybeAutoIndent(value, it) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    accessoryKey("←") { value = moveCursorHorizontally(value, -1) }
                    accessoryKey("→") { value = moveCursorHorizontally(value, 1) }
                    accessoryKey("↑") { value = moveCursorVertically(value, -1) }
                    accessoryKey("↓") { value = moveCursorVertically(value, 1) }
                    accessoryKey("Tab") { value = insertAtCursor(value, TAB) }
                }
                Text(hint)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
