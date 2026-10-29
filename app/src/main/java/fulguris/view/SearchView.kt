package fulguris.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.util.Log
import androidx.appcompat.R
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import java.util.concurrent.TimeUnit

private const val SV_LOG = "SVDEBUG"

class SearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.autoCompleteTextViewStyle
) : AppCompatAutoCompleteTextView(context, attrs, defStyleAttr) {

    interface PreFocusListener {
        fun onPreFocus()
    }

    /**
     * Lets the host update the field content when the edition starts and ends: show the URL while
     * editing and the label the rest of the time.
     */
    interface EditListener {
        fun onEditStart()
        fun onEditEnd()
    }

    var onPreFocusListener: PreFocusListener? = null
    var editListener: EditListener? = null

    /**
     * When true, the InputConnection wrapper silently drops empty IME commits. Set by the
     * host activity during the brief window after entering edit mode so the leanback TV IME
     * cannot wipe the freshly selected URL text on its first connection.
     */
    var isEditGuarded: Boolean = false

    /**
     * How long to wait after entering edit mode before checking that the field actually holds
     * focus (see the orphan safety net in startEditing). The framework grants focus to a tapped
     * field in ~10–100 ms, and even a slow grant on a busy page settles well under 800 ms, so
     * by that point a field that STILL holds no focus is a genuinely stranded edit state (the
     * WebView/page stole the focus), not a still-in-flight grant. 800 ms is long enough to
     * exclude slow legitimate grants yet short enough to heal before the user's next tap.
     */
    private companion object {
        const val EDIT_FOCUS_GRANT_TIMEOUT_MS = 800L

        // How long an armed-but-uncommitted tap edition (see editArmedByPointer) may linger
        // before it is cleared. The framework grants focus to a tapped field in ~10-100 ms, so
        // by 500 ms a tap whose focus never arrived has been taken by the page — clear the arm
        // so a later focus-for-navigation is not misread as "start editing".
        const val ARM_CLEAR_TIMEOUT_MS = 500L
    }

    private var isBeingClicked: Boolean = false
    private var timePressedNs: Long = 0

    /**
     * Set when a tap *started* the edition, so the matching ACTION_UP can select the whole URL
     * after the framework has placed its cursor at the tap point. A re-tap on an already-editing
     * field does not set this — it is left to reposition the cursor normally instead of
     * re-selecting everything.
     */
    private var selectAllOnUp: Boolean = false

    /**
     * Bumped on every call to startEditing. The orphan safety net posts a deferred check that
     * only acts if this token still matches when it runs — a stale check from a previous edition
     * (still queued while the user rapidly re-taps) must never evaluate the freshly-started
     * edition's in-flight focus grant and wrongly revert it.
     */
    private var editSession: Int = 0

    /**
     * Set when a tap ARMS an edition (see onTouchEvent) and cleared when it is either committed
     * (onFocusChanged(true) -> startEditing) or discarded (focus lost / timed out). Committing
     * the edition — and with it the label->URL swap — only happens once the field ACTUALLY holds
     * focus, so a URL can never be stranded on a field whose focus was stolen by the page.
     */
    private var editArmedByPointer: Boolean = false

    /**
     * True while the field is actively being edited (cursor shown, suggestions allowed).
     *
     * A pointer or touch interaction goes straight to edit mode. Directional navigation
     * (D-pad / keyboard / game pad) only focuses the field for navigation; the user then presses
     * the center / enter / A button to start editing. This makes address bar navigation behave the
     * same on touch screens, TVs, keyboards and game pads.
     */
    var isEditing: Boolean = false
        private set

    /**
     * Whether we believe the soft keyboard is currently shown for this field.
     */
    private var isKeyboardShown: Boolean = false

    /**
     * Set when a confirm key press starts the edition so the matching key up doesn't get
     * interpreted as a validation right away.
     */
    private var swallowNextConfirmUp: Boolean = false

    private val inputMethodManager: InputMethodManager?
        get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    /**
     * Consume, once, the confirm key up that belongs to the press which just started the edition.
     */
    fun shouldSwallowConfirmUp(): Boolean {
        if (swallowNextConfirmUp) {
            swallowNextConfirmUp = false
            return true
        }
        return false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Block the TV leanback IME's empty initialisation commit that wipes the URL.
                if (isEditGuarded && text != null && text.isEmpty() && this@SearchView.length() > 0) {
                    return true
                }
                return super.commitText(text, newCursorPosition)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                timePressedNs = System.nanoTime()
                isBeingClicked = true
                if (!isEditing) {
                    selectAllOnUp = true
                    if (hasFocus()) {
                        // Already focused (e.g. navigation mode): there is no focus race to win
                        // or lose, so commit the edition immediately.
                        startEditing(fromPointer = true)
                    } else {
                        // Not focused: only ARM the edition and commit it in onFocusChanged(true),
                        // once the framework has actually granted focus to this field. Committing
                        // on the DOWN (as we used to) sets isEditing and shows the URL BEFORE the
                        // focus grant; on a busy page the WebView can win that race, stranding the
                        // URL on screen with no cursor and the unfocused background ("full URL after
                        // one tap, not selected"). Arming defers the URL until we truly have focus —
                        // if the page steals the focus instead, nothing is shown and there is nothing
                        // to strand. The framework shows the keyboard on its own for the touch focus.
                        val armSession = ++editSession
                        editArmedByPointer = true
                        // Clear a stale arm if focus never arrives (the WebView/page took it). The
                        // session guard stops a stale timeout from clearing a newer arm.
                        postDelayed({
                            if (editSession == armSession) editArmedByPointer = false
                        }, ARM_CLEAR_TIMEOUT_MS)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> isBeingClicked = false
            MotionEvent.ACTION_UP -> if (isBeingClicked && !isLongPress(timePressedNs)) {
                onPreFocusListener?.onPreFocus()
            }
        }

        val handled = super.onTouchEvent(event)

        // On the tap that starts the edition we (re)select the whole URL on the next frame. The
        // framework places the cursor at the tap point during the touch, which CLEARS any full
        // selection; selecting on the next frame does it deterministically *after* the cursor is
        // placed, instead of relying on the host's 400 ms delayed edit guard. Without this there is
        // a window (hundreds of ms) in which a keystroke inserts into the URL instead of replacing
        // it: the "sometimes the URL isn't selected" bug. We post on both DOWN and UP so the
        // selection is re-established at least twice (the UP one is guaranteed to run after the
        // cursor placement), and it is never left to the delay alone. The flag is cleared on UP so
        // a re-tap on an already-editing field keeps its cursor at the tap point instead of
        // re-selecting everything.
        if (selectAllOnUp) {
            if (event.action == MotionEvent.ACTION_UP) {
                selectAllOnUp = false
            }
            post { if (isEditing) selectAll() }
        }

        return handled
    }

    private fun isLongPress(actionDownTime: Long): Boolean =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - actionDownTime) >= ViewConfiguration.getLongPressTimeout()

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused) {
            if (editArmedByPointer) {
                // A tap armed the edition and focus is now genuinely held — commit it HERE, which
                // is where the label becomes the URL. Because this runs only after the focus
                // grant, the URL can never be stranded on a field that does not have focus.
                editArmedByPointer = false
                startEditing(fromPointer = true)
                // Select the URL on the next frame so the first keystroke replaces it. The tap's
                // selectAllOnUp post covers the normal case (focus granted before ACTION_UP); this
                // also covers a slow grant that lands after the UP, when the tap's post has
                // already run while isEditing was still false.
                post { if (isEditing) selectAll() }
            } else if (!isEditing) {
                // Focused for directional navigation, not editing yet.
                isCursorVisible = false
                dismissDropDown()
            }
        } else {
            editArmedByPointer = false
            val wasEditing = isEditing
            resetEditingState()
            if (wasEditing) {
                // Losing focus while editing is a cancel: restore the label and close the
                // suggestion popup, exactly like cancelEditing(). The host's focus listener
                // already ran (before this callback) and saw isEditing == true, so it
                // skipped its own refresh — we must tell it explicitly. Without this the
                // typed characters stay in the pill even though the field no longer has
                // focus. (The host's listener already hides the keyboard.)
                editListener?.onEditEnd()
                dismissDropDown()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEditing) {
            // While focused for navigation, the center / enter / A key starts editing.
            if (isConfirmKey(keyCode)) {
                startEditing(fromPointer = false)
                swallowNextConfirmUp = true
                return true
            }
            // Don't consume the directional keys to move the cursor while only navigating; let
            // the framework move focus out of the field instead.
            if (isDirectionalKey(keyCode)) {
                return false
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        // ESC and BACK follow the same two-stage exit: keyboard first, then cancel editing.
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) && isEditing) {
            if (event.action == KeyEvent.ACTION_UP) {
                performBackAction()
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    /**
     * One step of the two-stage edit-mode back exit: the first call hides the
     * keyboard (keeping the suggestion popup), the second cancels the edition.
     * No-op while not editing. Called both for the BACK/ESC key (onKeyPreIme)
     * and for the system back gesture (the gesture produces no key events, so
     * the host activity's OnBackPressedCallback routes here instead).
     */
    fun performBackAction() {
        if (!isEditing) {
            return
        }
        if (isKeyboardShown) {
            // First press hides the keyboard, keeping the suggestion popup.
            hideKeyboard()
        } else {
            // Second press cancels the edition.
            cancelEditing()
        }
    }

    private fun isConfirmKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

    private fun isDirectionalKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    override fun showDropDown() {
        // Suppress during the guard window to avoid the popup flickering open-close-open
        // while the URL is being set and the TV IME's stale commits are being absorbed.
        if (!isEditing || isEditGuarded) {
            return
        }
        super.showDropDown()
    }

    /**
     * Enter edit mode: show the cursor and keyboard. The host swaps the label for the URL and
     * selects all of its text.
     */
    private fun startEditing(fromPointer: Boolean) {
        val session = ++editSession
        isEditing = true
        isCursorVisible = true
        editListener?.onEditStart()
        if (!fromPointer) {
            // Keyboard / D-pad path: select the URL now so the field is fully selected the
            // instant it enters edit mode, not only once the host's delayed guard runs.
            // The host set the URL and isEditGuarded in onEditStart() just above, so a stale
            // TV-IME empty commit is dropped and cannot wipe the freshly selected URL. (The
            // touch path selects in a next-frame post from onTouchEvent, once the framework
            // has placed the cursor at the tap point.)
            selectAll()
            // The framework shows the keyboard on its own for pointer / touch focus.
            inputMethodManager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
        isKeyboardShown = true

        // Safety net: entering edit mode commits isEditing=true (and the host swaps the label
        // for the full URL) BEFORE the framework has necessarily granted focus to this field.
        // All the normal cleanup lives in onFocusChanged(false), which only ever fires if the
        // field FIRST gained focus and then lost it. On a busy page a tap can hand focus to the
        // WebView before it lands on the field, so onFocusChanged(true) never fires — and
        // neither does its pair — leaving isEditing stranded true: the URL stays on screen but
        // with no cursor and the unfocused background ("the full URL after one tap, not
        // selected"). Enforce the invariant "isEditing is never true while the field, in the
        // foreground, holds no focus" a moment later and self-heal if it is broken.
        //
        // The check is tagged with the edit `session`: if the user has already started a newer
        // edition by the time it runs (rapid re-tap), it is stale and is discarded — otherwise
        // it would evaluate the newer edition's still-in-flight focus grant and wrongly revert
        // a field the user is legitimately tapping.
        postDelayed({
            if (session == editSession && isEditing && hasWindowFocus() && !hasFocus()) {
                Log.w(SV_LOG, "ORPHAN-REPAIR: isEditing=true hasWindowFocus=true hasFocus=false — reverting to label (text=$text)")
                resetEditingState()
                editListener?.onEditEnd()
                dismissDropDown()
            }
        }, EDIT_FOCUS_GRANT_TIMEOUT_MS)
    }

    /**
     * Hide the keyboard while staying in edit mode so the suggestion popup remains usable.
     */
    private fun hideKeyboard() {
        isKeyboardShown = false
        inputMethodManager?.hideSoftInputFromWindow(windowToken, 0)
    }

    /**
     * Cancel the current edition: go back to the navigation label, dismiss the popup, hide the
     * keyboard and keep the field focused for navigation.
     */
    private fun cancelEditing() {
        resetEditingState()
        editListener?.onEditEnd()
        dismissDropDown()
        inputMethodManager?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun resetEditingState() {
        isEditing = false
        isKeyboardShown = false
        isCursorVisible = false
    }

}

