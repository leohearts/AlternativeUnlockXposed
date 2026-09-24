package com.leohearts.alternativeUnlockHook

import android.graphics.Rect
import android.util.Log
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.FontSizeScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.text.indexOf
import kotlin.time.Duration.Companion.milliseconds

// ---- Command editor ----
// Hand-rolled on BasicTextField on purpose: it keeps the platform IME path, which is the
// part third-party editor libraries get wrong on Android.
//
// Scrolling: the field scrolls INTERNALLY (height-constrained to the editor area, with
// a caller-provided ScrollState), instead of being wrapped in an outer scrollable. An
// outer scrollable makes the focused field fight user scrolls: every position change
// re-reports the cursor rect, and while the IME connection is being (re)created that
// escalates to requestRectangleOnScreen, which scrolls the ancestor back to the cursor.
// With internal scrolling there is no scrollable ancestor, so nothing can fight the user.
//
// Line numbers: a gutter lane to the LEFT of the field, the width of the number column.
// The field is narrower by that width, so soft-wrap happens exactly at the lane edge and
// continuation lines are indented under the numbers for free — no wrap computation, no
// offset mapping. The numbers are painted on a Canvas from the field's own layout result
// (y of each logical line start) and translated by the shared scroll state, so they track
// the text exactly.

// Compose text layout has no tab-stop support and renders '\t' one space wide, so the
// editor works in spaces. Existing tabs are expanded on load.
private const val TAB = "    "

private fun expandTabs(text: String) = text.replace("\t", TAB)

private val bashKeywords = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
    "case", "esac", "in", "function", "return", "exit", "export", "local", "source"
)

enum class BashToken { COMMENT, STRING, VARIABLE, KEYWORD }

// Single-pass bash scanner. Rules verified against real bash(1) behavior:
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
fun bashSpans(code: String): List<Triple<Int, Int, BashToken>> {
    val spans = mutableListOf<Triple<Int, Int, BashToken>>()
    val n = code.length
    var i = 0
    var parenDepth = 0 // tracks $( ... ) nesting
    while (i < n) {
        val c = code[i]
        when {
            // comment: '#' at a word boundary
            c == '#' && (i == 0 || code[i - 1] in " \t\r\n;&|()<>`") -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                spans.add(Triple(i, end, BashToken.COMMENT))
                i = end
            }
            c == '"' -> {
                var j = i + 1
                while (j < n && code[j] != '"') {
                    if (code[j] == '\\') j++
                    j++
                }
                val end = minOf(j + 1, n)
                spans.add(Triple(i, end, BashToken.STRING))
                i = end
            }
            c == '\'' -> {
                val end = code.indexOf('\'', i + 1).let { if (it < 0) n else it + 1 }
                spans.add(Triple(i, end, BashToken.STRING))
                i = end
            }
            c == '`' -> {
                var j = i + 1
                while (j < n && code[j] != '`') {
                    if (code[j] == '\\') j++
                    j++
                }
                val end = minOf(j + 1, n)
                spans.add(Triple(i, end, BashToken.STRING))
                i = end
            }
            c == '$' -> {
                when {
                    i + 1 < n && code[i + 1] == '{' -> {
                        val end = code.indexOf('}', i + 2).let { if (it < 0) n else it + 1 }
                        spans.add(Triple(i, end, BashToken.VARIABLE))
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
                            spans.add(Triple(i, j, BashToken.VARIABLE))
                            i = j
                        } else if (j < n && code[j] in "@*#?$!-_0123456789") {
                            spans.add(Triple(i, j + 1, BashToken.VARIABLE))
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
                    if (bodyStart < end) spans.add(Triple(bodyStart, end, BashToken.STRING))
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
                    spans.add(Triple(i, j, BashToken.KEYWORD))
                }
                i = j
            }
            else -> i++
        }
    }
    return spans
}

@Composable
private fun BashEditorField(
    state: TextFieldState,
    scrollState: ScrollState,
    layout: TextLayoutResult?,
    onLayout: (TextLayoutResult?) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = cs.onSurface
    )
    val tokenColors = mapOf(
        BashToken.COMMENT to SpanStyle(color = cs.outline),
        BashToken.STRING to SpanStyle(color = cs.tertiary),
        BashToken.VARIABLE to SpanStyle(color = cs.secondary),
        BashToken.KEYWORD to SpanStyle(color = cs.primary)
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val editorHeight = maxHeight
        val laneText = "0".repeat((state.text.count { it == '\n' } + 1).toString().length + 1)
        val laneWidth = with(density) { textMeasurer.measure(laneText, style = codeStyle).size.width.toDp() }
        val lineHeightPx = with(density) { (codeStyle.lineHeight).toPx() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            // gutter lane: line numbers painted from the field's layout, scrolled in sync
            Box(Modifier.width(laneWidth).fillMaxHeight().clipToBounds()) {
                Canvas(Modifier.fillMaxSize()) {
                    val lr = layout ?: return@Canvas
                    val scroll = scrollState.value.toFloat()
                    var number = 1
                    for (v in 0 until lr.lineCount) {
                        val start = lr.getLineStart(v)
                        if (start == 0 || state.text.getOrNull(start - 1) == '\n') {
                            val y = lr.getLineTop(v) - scroll
                            if (y in -lineHeightPx..(size.height + lineHeightPx)) {
                                drawText(
                                    textMeasurer,
                                    number.toString(),
                                    topLeft = Offset(0f, y),
                                    style = codeStyle.copy(color = cs.outline)
                                )
                            }
                            number++
                        }
                    }
                }
            }
            // The field is narrower by the lane width, so soft wrap breaks exactly at the
            // lane edge and wrapped continuations are indented under the numbers.
            BasicTextField(
                state = state,
                scrollState = scrollState,
                textStyle = codeStyle,
                cursorBrush = SolidColor(cs.primary),
                onTextLayout = { onLayout(it()) },
                outputTransformation = OutputTransformation {
                    for ((s, e, kind) in bashSpans(toString())) {
                        addStyle(tokenColors.getValue(kind), s, e)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(editorHeight)
            )
        }
    }
}

// Accessory keys built on framework primitives. combinedClickable fires onClick on a
// tap (the framework cancels it when the gesture turns into a LazyRow scroll, so sliding
// the toolbar never hits keys), and onLongClick starts a repeat loop; the loop stops when
// the press interaction is cancelled (release, or the scroll taking the gesture over),
// tracked via collectIsPressedAsState. Plain Text instead of Button so the keys never
// take focus away from the editor (which would dismiss the IME).
@Composable
private fun repeatingKey(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var repeating by remember { mutableStateOf(false) }
    LaunchedEffect(isPressed) {
        if (!isPressed) repeating = false
    }
    LaunchedEffect(repeating) {
        if (repeating) {
            onClick()
            while (true) {
                delay(60.milliseconds)
                onClick()
            }
        }
    }
    Text(
        label,
        modifier = Modifier
            .background(
                if (isPressed) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { repeating = true }
            )
            .padding(horizontal = 0.dp, vertical = 0.dp)
            .width(44.dp)
            .wrapContentHeight(align = Alignment.CenterVertically),
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
        overflow = TextOverflow.Visible,
        maxLines = 1,
        textAlign = TextAlign.Center
    )
}

// The toolbar is a LazyRow: its scroll cancels item presses, so sliding never fires keys.
@Composable
private fun accessoryToolbar(keys: List<Pair<String, () -> Unit>>) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        modifier = Modifier.height(88.dp)
    ) {
        items(keys) { (label, action) -> repeatingKey(label, action) }
    }
}

private fun moveCursorHorizontally(state: TextFieldState, dir: Int) {
    val sel = state.selection
    val target = when {
        !sel.collapsed && dir < 0 -> sel.start
        !sel.collapsed && dir > 0 -> sel.end
        else -> (sel.end + dir).coerceIn(0, state.text.length)
    }
    state.edit { selection = TextRange(target) }
}

private fun moveCursorVertically(state: TextFieldState, dir: Int) {
    val text = state.text.toString()
    val head = state.selection.end
    val lineStart = text.lastIndexOf('\n', head - 1).let { if (it < 0) 0 else it + 1 }
    val column = head - lineStart
    val target = if (dir < 0) {
        if (lineStart == 0) return
        val prevEnd = lineStart - 1
        val prevStart = text.lastIndexOf('\n', prevEnd - 1).let { if (it < 0) 0 else it + 1 }
        prevStart + minOf(column, prevEnd - prevStart)
    } else {
        val lineEnd = text.indexOf('\n', head).let { if (it < 0) text.length else it }
        if (lineEnd == text.length) return
        val nextStart = lineEnd + 1
        val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
        nextStart + minOf(column, nextEnd - nextStart)
    }
    state.edit { selection = TextRange(target) }
}

private fun insertAtCursor(state: TextFieldState, insert: String) {
    val sel = state.selection
    state.edit {
        replace(sel.start, sel.end, insert)
        selection = TextRange(sel.start + insert.length)
    }
}

// A line opens a block (and the next line should be indented one level deeper) when it
// ends with a control keyword of a construct (then/do/else/elif/in of if/for/while/until/
// case) or with an opening brace/paren. The first-word check avoids false positives like
// `echo do`.
private val bashControlKeywords = setOf("if", "elif", "for", "while", "until", "case")
private val bashOpenerAlone = setOf("then", "do", "else", "elif")

private fun blockOpener(line: String): Boolean {
    val trimmed = line.trimEnd()
    if (trimmed.isEmpty()) return false
    val last = trimmed.last()
    if (last == '{' || last == '(') return true
    if (!last.isLetter()) return false
    val wordStart = trimmed.indexOfLast { !it.isLetter() } + 1
    val lastWord = trimmed.substring(wordStart)
    if (lastWord !in bashOpenerAlone && lastWord != "in") return false
    val firstWord = trimmed.trimStart().substringBefore(' ').takeWhile { it.isLetter() }
    return firstWord in bashControlKeywords || trimmed == lastWord
}

private fun deleteForward(state: TextFieldState) {
    val sel = state.selection
    state.edit {
        if (sel.collapsed) {
            val end = (sel.start + 1).coerceAtMost(length)
            if (end > sel.start) replace(sel.start, end, "")
        } else {
            replace(sel.start, sel.end, "")
        }
    }
}

// Full-size variant of the standard edit dialog for the action command: same AlertDialog
// structure (title / content / Confirm / Cancel), content is a bash editor with line
// numbers, soft wrap and an accessory key row.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandEditDialog(
    title: String,
    hint: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val fieldState = rememberTextFieldState(expandTabs(initial))
    val scrollState = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        var prev = fieldState.text.toString()
        snapshotFlow { fieldState.text.toString() }.collect { newText ->
            val oldText = prev
            prev = newText
            // auto-indent: when the edit was a single '\n' insertion (IME or toolbar
            // Enter), copy the current line's leading whitespace onto the new line, plus
            // one extra level if the line opens a block (if...then, for...do, ...).
            if (newText.length == oldText.length + 1) {
                val pos = fieldState.selection.start
                if (pos > 0 && newText.getOrNull(pos - 1) == '\n') {
                    val lineStart = oldText.lastIndexOf('\n', pos - 2).let { if (it < 0) 0 else it + 1 }
                    val line = oldText.substring(lineStart, pos - 1)
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val extra = if (blockOpener(line)) TAB else ""
                    if (indent.isNotEmpty() || extra.isNotEmpty()) {
                        fieldState.edit {
                            replace(pos, pos, indent + extra)
                            selection = TextRange(pos + indent.length + extra.length)
                        }
                    }
                }
            }
        }
    }

    // fieldState.undoState is @ExperimentalFoundationApi in foundation 1.12; it drives the
    // field's built-in undo history, which also records programmatic edits (auto-indent,
    // accessory keys), so undo/redo behave like a real editor.
    @OptIn(ExperimentalFoundationApi::class)
    fun undo() {
        fieldState.undoState.undo()
    }

    @OptIn(ExperimentalFoundationApi::class)
    fun redo() {
        fieldState.undoState.redo()
    }

    // reveal the cursor after accessory-key moves (typing and taps are revealed by the
    // field itself); user scrolls never touch the selection, so this cannot fight them
    fun revealCursor() {
        val lr = layout ?: return
        val viewport = scrollState.viewportSize
        if (viewport <= 0) return
        val pos = fieldState.selection.end.coerceIn(0, fieldState.text.length)
        val rect = lr.getCursorRect(pos)
        val top = scrollState.value.toFloat()
        val bottom = top + viewport
        scope.launch {
            if (rect.top < top) {
                scrollState.scrollTo(rect.top.toInt().coerceAtLeast(0))
            } else if (rect.bottom > bottom) {
                scrollState.scrollTo((rect.bottom - viewport).toInt().coerceAtLeast(0))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Cancel",
                        )
                    }
                    TextButton(onClick = { onConfirm(fieldState.text.toString()) }) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Confirm",
                        )
                    }
                }
            }
        },
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
                    state = fieldState,
                    scrollState = scrollState,
                    layout = layout,
                    onLayout = { layout = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                accessoryToolbar(
                    listOf(
                        "Home" to { TODO() },
                        "←" to { moveCursorHorizontally(fieldState, -1); revealCursor() },
                        "↑" to { moveCursorVertically(fieldState, -1); revealCursor() },
                        "↓" to { moveCursorVertically(fieldState, 1); revealCursor() },
                        "End" to { TODO() },
                        "→" to { moveCursorHorizontally(fieldState, 1); revealCursor() },
                        "-" to { insertAtCursor(fieldState, "-") },
                        "<" to { insertAtCursor(fieldState, "<") },
                        "_" to { insertAtCursor(fieldState, "_") },
                        ">" to { insertAtCursor(fieldState, ">") },
                        "|" to { insertAtCursor(fieldState, "|") },
                        "`" to { insertAtCursor(fieldState, "`") },
                        "Undo" to { undo() },
                        "Redo" to { redo() },
                        "Tab" to { insertAtCursor(fieldState, TAB) },
                        "Del" to { deleteForward(fieldState) },
                    )
                )

                LazyRow() {
                    item {
                        Text(
                            text = hint,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        confirmButton = {
        },
        dismissButton = {
        }
    )
}
