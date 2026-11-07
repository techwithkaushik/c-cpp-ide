package com.techwithkaushik.cppide.feature.editor

import com.techwithkaushik.cppide.core.logging.DeveloperLog
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspLanguage
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.code.codeAction
import io.github.rosemoe.sora.lsp.utils.asLspRange
import kotlinx.coroutines.launch
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Loads local C/C++ TextMate grammars once and attaches the matching language to each editor.
 * clangd is connected separately through Sora's LSP language so semantic completion and diagnostics
 * remain asynchronous and never block editor keystrokes.
 */
private object CppTextMateSupport {
    private var initialized = false
    private var activeTheme = ""
    private val scopeByEditor = WeakHashMap<CodeEditor, String>()

    @Synchronized
    private fun ensureInitialized(context: android.content.Context) {
        if (initialized) return
        val appContext = context.applicationContext
        val providers = FileProviderRegistry.getInstance()
        providers.addFileProvider(AssetsFileResolver(appContext.assets))

        val themes = listOf(
            "cppide-dark" to true,
            "cppide-light" to false,
            "cppide-monokai" to true,
            "cppide-solarized" to true
        )
        val registry = ThemeRegistry.getInstance()
        themes.forEach { (name, dark) ->
            val path = "textmate/$name.json"
            val stream = providers.tryGetInputStream(path)
                ?: error("Missing TextMate theme asset: $path")
            registry.loadTheme(
                ThemeModel(IThemeSource.fromInputStream(stream, path, null), name).apply {
                    isDark = dark
                }
            )
        }
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        registry.setTheme("cppide-dark")
        activeTheme = "cppide-dark"
        initialized = true
    }

    fun apply(editor: CodeEditor, fileName: String, themeName: String): TextMateLanguage? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val scope = when (extension) {
            "c" -> "source.c"
            "cc", "cpp", "cxx", "c++", "hh", "hpp", "hxx", "h" -> "source.cpp"
            else -> null
        }
        if (scope == null) {
            scopeByEditor.remove(editor)
            if (editor.editorLanguage !is EmptyLanguage) editor.setEditorLanguage(EmptyLanguage())
            return null
        }

        ensureInitialized(editor.context)
        val theme = when (themeName) {
            "Light+ (VS Code)" -> "cppide-light"
            "Monokai" -> "cppide-monokai"
            "Solarized Dark" -> "cppide-solarized"
            else -> "cppide-dark"
        }
        if (activeTheme != theme) {
            ThemeRegistry.getInstance().setTheme(theme)
            activeTheme = theme
        }
        if (editor.colorScheme !is TextMateColorScheme) {
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        }

        // Keep Sora's LspLanguage installed. TextMate is its delegate; replacing
        // the wrapper on every Compose update disables LSP completion and hover.
        val currentLanguage = editor.editorLanguage
        val currentTextMate = when (currentLanguage) {
            is LspLanguage -> currentLanguage.wrapperLanguage as? TextMateLanguage
            is TextMateLanguage -> currentLanguage
            else -> null
        }
        val textMateLanguage = currentTextMate ?: TextMateLanguage.create(scope, false)
        if (currentTextMate != null && scopeByEditor[editor] != scope) {
            textMateLanguage.updateLanguage(scope)
        }
        if (currentLanguage is LspLanguage) {
            currentLanguage.wrapperLanguage = textMateLanguage
        } else if (currentLanguage !== textMateLanguage) {
            editor.setEditorLanguage(textMateLanguage)
        }
        scopeByEditor[editor] = scope
        return textMateLanguage
    }

    fun detach(editor: CodeEditor) {
        scopeByEditor.remove(editor)
    }
}

/** Imperative command bridge for the Sora editor hosted by Compose. */
class SoraEditorController {
    private var editor: CodeEditor? = null
    private var formatAction: (() -> Unit)? = null

    fun undo() { editor?.undo() }
    fun redo() { editor?.redo() }
    fun selectAll() { editor?.selectAll() }
    fun copy() { editor?.copyText() }
    fun paste() { editor?.pasteText() }
    fun cut() { editor?.cutText() }
    fun format() { formatAction?.invoke() ?: editor?.formatCodeAsync() }
    fun setFormatAction(action: (() -> Unit)?) { formatAction = action }

    fun selectLine(line: Int) = selectPosition(line, 1)

    fun selectPosition(line: Int, column: Int) {
        val targetLine = (line - 1).coerceAtLeast(0)
        val targetColumn = (column - 1).coerceAtLeast(0)
        editor?.let { view ->
            if (targetLine < view.lineCount) {
                view.setSelection(targetLine, targetColumn)
                view.ensurePositionVisible(targetLine, targetColumn)
                view.requestFocus()
            }
        }
    }

    internal fun attach(editor: CodeEditor) { this.editor = editor }
    internal fun detach(editor: CodeEditor) { if (this.editor === editor) this.editor = null }
}

private fun showEditorContextMenu(anchor: View, lspEditor: LspEditor?, controller: SoraEditorController?) {
    val editor = anchor as? CodeEditor ?: return
    PopupMenu(anchor.context, anchor).apply {
        menu.add(0, 1, 0, "Undo")
        menu.add(0, 2, 1, "Redo")
        menu.add(0, 3, 2, "Cut")
        menu.add(0, 4, 3, "Copy")
        menu.add(0, 5, 4, "Paste")
        menu.add(0, 6, 5, "Select All")
        if (lspEditor?.isConnected == true) {
            menu.add(0, 7, 6, "Quick Fix / Code Actions")
            menu.add(0, 8, 7, "Format Document")
        }
        setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> editor.undo()
                2 -> editor.redo()
                3 -> editor.cutText()
                4 -> editor.copyText()
                5 -> editor.pasteText()
                6 -> editor.selectAll()
                7 -> {
                    val languageEditor = lspEditor ?: return@setOnMenuItemClickListener false
                    languageEditor.coroutineScope.launch {
                        languageEditor.eventManager.emitAsync(EventType.codeAction) {
                            put(editor.cursor.range.asLspRange())
                        }
                    }
                }
                8 -> controller?.format() ?: editor.formatCodeAsync()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        show()
    }
}

/** VS Code-inspired editor palettes. Keep the editor canvas and gutter in sync. */
private fun configureEditorTheme(editor: CodeEditor, themeName: String) {
    val colors = when (themeName) {
        "Light+ (VS Code)" -> intArrayOf(
            AndroidColor.rgb(243, 243, 243),
            AndroidColor.rgb(30, 30, 30),
            AndroidColor.rgb(237, 237, 237),
            AndroidColor.rgb(133, 133, 133),
            AndroidColor.rgb(232, 232, 232),
            AndroidColor.rgb(220, 235, 252)
        )
        "Monokai" -> intArrayOf(
            AndroidColor.rgb(39, 40, 34),
            AndroidColor.rgb(248, 248, 242),
            AndroidColor.rgb(45, 46, 40),
            AndroidColor.rgb(144, 145, 133),
            AndroidColor.rgb(55, 56, 48),
            AndroidColor.rgb(73, 72, 62)
        )
        "Solarized Dark" -> intArrayOf(
            AndroidColor.rgb(0, 43, 54),
            AndroidColor.rgb(131, 148, 150),
            AndroidColor.rgb(7, 54, 66),
            AndroidColor.rgb(101, 123, 131),
            AndroidColor.rgb(7, 54, 66),
            AndroidColor.rgb(38, 70, 83)
        )
        else -> intArrayOf(
            AndroidColor.rgb(30, 30, 30),
            AndroidColor.rgb(212, 212, 212),
            AndroidColor.rgb(37, 37, 38),
            AndroidColor.rgb(133, 133, 133),
            AndroidColor.rgb(42, 45, 46),
            AndroidColor.rgb(38, 79, 120)
        )
    }
    editor.colorScheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, colors[0])
    editor.colorScheme.setColor(EditorColorScheme.TEXT_NORMAL, colors[1])
    editor.colorScheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors[2])
    editor.colorScheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, colors[2])
    editor.colorScheme.setColor(EditorColorScheme.LINE_NUMBER, colors[3])
    editor.colorScheme.setColor(EditorColorScheme.CURRENT_LINE, colors[4])
    editor.colorScheme.setColor(EditorColorScheme.SELECTION_INSERT, colors[5])
    editor.colorScheme.setColor(EditorColorScheme.SELECTION_HANDLE, colors[5])
}

@Composable
fun SoraCodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    controller: SoraEditorController? = null,
    fontSize: Float = 14f,
    wordWrap: Boolean = false,
    themeName: String = "Dark+ (VS Code)",
    fileName: String = "",
    lspSession: ClangdProjectSession? = null,
    onCursorPositionChange: (line: Int, column: Int) -> Unit = { _, _ -> }
) {
    val currentOnCodeChange = rememberUpdatedState(onCodeChange)
    val currentOnCursorPositionChange = rememberUpdatedState(onCursorPositionChange)
    val suppressProgrammaticChanges = remember { AtomicBoolean(false) }
    val lspEditor = remember(lspSession, fileName) { lspSession?.createEditor(fileName) }

    DisposableEffect(lspEditor) {
        DeveloperLog.event("SoraEditor", "lsp_document_composition_started", mapOf("file" to fileName, "hasSession" to (lspSession != null), "hasLspEditor" to (lspEditor != null)))
        onDispose {
            DeveloperLog.event("SoraEditor", "lsp_document_composition_disposed", mapOf("file" to fileName, "connected" to (lspEditor?.isConnected == true), "attached" to (lspEditor?.editor != null)))
            runCatching { lspEditor?.dispose() }
                .onFailure { DeveloperLog.failure("SoraEditor", "lsp_editor_dispose_failed", it, mapOf("file" to fileName)) }
        }
    }

    // A CodeEditor is bound to one LSP document. Recreate the Android view when
    // the document or workspace changes: AndroidView's factory is not re-run by
    // Compose update, so otherwise callbacks and the LspLanguage retain the old file.
    key(lspSession, fileName) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val factoryStartedAt = System.nanoTime()
            DeveloperLog.event("SoraEditor", "android_editor_view_creation_started", mapOf("file" to fileName, "initialCharacters" to code.length, "fontSize" to fontSize, "wordWrap" to wordWrap, "hasLspEditor" to (lspEditor != null)))
            CodeEditor(context).apply {
                setText(code)
                setTextSize(fontSize)
                setTypefaceText(Typeface.MONOSPACE)
                isWordwrap = wordWrap
                setLineNumberEnabled(true)
                setHighlightCurrentLine(true)
                setTabWidth(4)
                setScrollBarEnabled(true)
                setHorizontalScrollBarEnabled(true)
                val textMateLanguage = runCatching { CppTextMateSupport.apply(this, fileName, themeName) }
                    .onFailure { DeveloperLog.failure("SoraEditor", "textmate_language_setup_failed", it, mapOf("file" to fileName, "theme" to themeName)) }
                    .getOrNull()
                configureEditorTheme(this, themeName)
                DeveloperLog.event("SoraEditor", "android_editor_view_created", mapOf("file" to fileName, "language" to (editorLanguage?.javaClass?.simpleName ?: "none"), "textMateLanguageCreated" to (textMateLanguage != null), "durationMs" to (System.nanoTime() - factoryStartedAt) / 1_000_000))
                lspEditor?.let { languageEditor ->
                    languageEditor.wrapperLanguage = requireNotNull(textMateLanguage) {
                        "A C/C++ language server cannot be attached to a non-C/C++ document."
                    }
                    languageEditor.editor = this
                    DeveloperLog.event("SoraEditor", "lsp_editor_attached_factory", mapOf(
                        "file" to fileName,
                        "editorAttached" to (languageEditor.editor === this),
                        "language" to (editorLanguage?.javaClass?.simpleName ?: "none")
                    ))
                    lspSession?.let { session ->
                        languageEditor.coroutineScope.launch {
                            DeveloperLog.event("SoraEditor", "lsp_connect_coroutine_started", mapOf("file" to fileName, "attached" to (languageEditor.editor === this@apply)))
                            runCatching { session.connect(languageEditor) }
                                .onFailure { DeveloperLog.failure("SoraEditor", "lsp_connect_coroutine_failed", it, mapOf("file" to fileName)) }
                        }
                    }
                }
                controller?.attach(this)
                currentOnCursorPositionChange.value(cursor.leftLine + 1, cursor.leftColumn + 1)
                setOnLongClickListener { view ->
                    showEditorContextMenu(view, lspEditor, controller)
                    true
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setOnContextClickListener { view ->
                        showEditorContextMenu(view, lspEditor, controller)
                        true
                    }
                }
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    if (!suppressProgrammaticChanges.get()) {
                        currentOnCodeChange.value(text.toString())
                    }
                }
                subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                    currentOnCursorPositionChange.value(event.left.line + 1, event.left.column + 1)
                }
            }
        },
        update = { editor ->
            editor.setTextSize(fontSize)
            editor.setTypefaceText(Typeface.MONOSPACE)
            val textMateLanguage = runCatching { CppTextMateSupport.apply(editor, fileName, themeName) }
                .onFailure { DeveloperLog.failure("SoraEditor", "textmate_language_update_failed", it, mapOf("file" to fileName, "theme" to themeName)) }
                .getOrNull()
            configureEditorTheme(editor, themeName)
            editor.isWordwrap = wordWrap
            editor.setLineNumberEnabled(true)
            editor.setHighlightCurrentLine(true)
            editor.setTabWidth(4)
            editor.setScrollBarEnabled(true)
            editor.setHorizontalScrollBarEnabled(true)
            controller?.attach(editor)
            if (lspEditor != null && textMateLanguage != null) {
                val activeLspLanguage = editor.editorLanguage as? LspLanguage
                if (lspEditor.editor !== editor || activeLspLanguage?.editor !== lspEditor) {
                    lspEditor.wrapperLanguage = textMateLanguage
                    lspEditor.editor = editor
                    DeveloperLog.event("SoraEditor", "lsp_editor_reattached", mapOf(
                        "file" to fileName,
                        "language" to (editor.editorLanguage?.javaClass?.simpleName ?: "none")
                    ))
                    lspSession?.let { session ->
                        lspEditor.coroutineScope.launch {
                            DeveloperLog.event("SoraEditor", "lsp_reconnect_coroutine_started", mapOf("file" to fileName, "attached" to (lspEditor.editor === editor)))
                            runCatching { session.connect(lspEditor) }
                                .onFailure { DeveloperLog.failure("SoraEditor", "lsp_reconnect_coroutine_failed", it, mapOf("file" to fileName)) }
                        }
                    }
                } else {
                    activeLspLanguage.wrapperLanguage = textMateLanguage
                }
            }
            if (editor.text.toString() != code) {
                suppressProgrammaticChanges.set(true)
                try {
                    editor.setText(code)
                } finally {
                    suppressProgrammaticChanges.set(false)
                }
            }
            currentOnCursorPositionChange.value(editor.cursor.leftLine + 1, editor.cursor.leftColumn + 1)
        },
        onRelease = { editor ->
            DeveloperLog.event("SoraEditor", "android_editor_view_released", mapOf("file" to fileName, "lspConnected" to (lspEditor?.isConnected == true), "textLength" to editor.text.length))
            controller?.detach(editor)
            CppTextMateSupport.detach(editor)
            editor.release()
        }
    )
    }
}
