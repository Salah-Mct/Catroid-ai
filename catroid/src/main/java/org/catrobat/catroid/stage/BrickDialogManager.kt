/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2025 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.stage

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.badlogic.gdx.scenes.scene2d.Action
import org.catrobat.catroid.BuildConfig
import org.catrobat.catroid.R
import org.catrobat.catroid.TrustedDomainManager
import org.catrobat.catroid.common.Constants
import org.catrobat.catroid.content.actions.AskAction
import org.catrobat.catroid.content.actions.FaceNameTrainAction
import org.catrobat.catroid.content.actions.WebAction
import java.net.URI
import java.util.ArrayList
import java.util.Collections

class BrickDialogManager(val stageActivity: StageActivity) :
    DialogInterface.OnKeyListener, DialogInterface.OnDismissListener {

    private val openDialogs = Collections.synchronizedList(ArrayList<Dialog>())

    enum class DialogType {
        ASK_DIALOG,
        WEB_ACCESS_DIALOG,
        FACE_TRAIN_MENU,
        FACE_TRAIN_NEW_NAME,
        FACE_TRAIN_DELETE_CHOICE,
        FACE_TRAIN_DELETE_CONFIRM,
        FACE_TRAIN_PROGRESS
    }

    fun dialogIsShowing() = openDialogs.isNotEmpty()

    /** Set by [dismissAllDialogs]: the stage is being destroyed, nothing may resume it. */
    @Volatile
    private var closingStage = false

    fun dismissAllDialogs() {
        closingStage = true
        faceTrainNextStep = null
        openDialogs.toList().forEach { it.dismiss() }
        openDialogs.clear()
    }

    fun showDialog(type: DialogType, action: Action, content: String) {
        if (closingStage || stageActivity.isFinishing || stageActivity.isDestroyed) {
            return
        }
        val dialog = when (type) {
            DialogType.ASK_DIALOG -> createAskDialog(action as AskAction, content)
            DialogType.WEB_ACCESS_DIALOG -> createWebAccessDialog(action as WebAction, content)
            DialogType.FACE_TRAIN_MENU -> createFaceTrainMenuDialog(action as FaceNameTrainAction)
            DialogType.FACE_TRAIN_NEW_NAME -> createFaceTrainNewNameDialog(action as FaceNameTrainAction)
            DialogType.FACE_TRAIN_DELETE_CHOICE -> createFaceTrainDeleteChoiceDialog(action as FaceNameTrainAction)
            DialogType.FACE_TRAIN_DELETE_CONFIRM ->
                createFaceTrainDeleteConfirmDialog(action as FaceNameTrainAction, content.toInt())
            DialogType.FACE_TRAIN_PROGRESS -> createFaceTrainProgressDialog(action as FaceNameTrainAction)
        }
        openDialog(dialog)
    }

    private fun openDialog(dialog: Dialog) {
        StageLifeCycleController.stagePause(stageActivity)
        openDialogs.add(dialog)
        dialog.show()
    }

    private fun createAskDialog(askAction: AskAction, question: String): Dialog {
        val editText = EditText(stageActivity)
        val askDialog = AlertDialog.Builder(ContextThemeWrapper(stageActivity, R.style.Theme_AppCompat_Dialog))
            .setView(editText)
            .setMessage(stageActivity.getString(R.string.brick_ask_dialog_hint))
            .setTitle(question)
            .setCancelable(false)
            .setOnKeyListener(this)
            .setOnDismissListener(this)
            .setPositiveButton(stageActivity.getString(R.string.brick_ask_dialog_submit)) { _, _ ->
                askAction.setAnswerText(editText.text.toString())
            }
            .create()

        editText.requestFocus()
        askDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        return askDialog
    }

    private fun createWebAccessDialog(webAction: WebAction, url: String): Dialog {
        val view = LayoutInflater.from(stageActivity).inflate(R.layout.dialog_web_access, null)
        view.findViewById<TextView>(R.id.request_url).text = url

        view.findViewById<TextView>(R.id.request_warning).apply {
            text = HtmlCompat.fromHtml(
                stageActivity.getString(R.string.web_request_warning_message, Constants.WEB_REQUEST_WIKI_URL),
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            movementMethod = LinkMovementMethod.getInstance()
        }

        return AlertDialog.Builder(ContextThemeWrapper(stageActivity, R.style.Theme_AppCompat_Dialog))
            .setTitle(stageActivity.getString(R.string.web_request_warning_title))
            .setCancelable(false)
            .setView(view)
            .setOnKeyListener(this)
            .setOnDismissListener(this)
            .setPositiveButton(stageActivity.getString(R.string.once)) { _, _ ->
                webAction.grantPermission()
            }
            .setNeutralButton(stageActivity.getString(R.string.always)) { dialog, _ ->
                openDialog(createTrustDomainDialog(webAction, url, dialog as Dialog))
            }
            .setNegativeButton(stageActivity.getString(R.string.deny)) { _, _ ->
                webAction.denyPermission()
            }
            .create()
    }

    private fun createTrustDomainDialog(webAction: WebAction, url: String, webAccessDialog: Dialog): Dialog {
        val domain = URI(url).host.removePrefix("www.")
        val view = LayoutInflater.from(stageActivity).inflate(R.layout.dialog_web_access, null)
        view.findViewById<TextView>(R.id.request_url).text = domain

        val warningMessage = StringBuilder()
            .append(stageActivity.getString(R.string.web_request_warning_message, Constants.WEB_REQUEST_WIKI_URL))
            .append("<br><br>")
            .append(stageActivity.getString(R.string.web_request_trust_domain_warning_message))

        if (!BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
            warningMessage.append(" ").append(stageActivity.getString(R.string.trusted_domains_edit_hint))
        }

        view.findViewById<TextView>(R.id.request_warning).apply {
            text = HtmlCompat.fromHtml(warningMessage.toString(), HtmlCompat.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
        }

        return AlertDialog.Builder(ContextThemeWrapper(stageActivity, R.style.Theme_AppCompat_Dialog))
            .setTitle(stageActivity.getString(R.string.web_request_trust_domain_warning_title))
            .setCancelable(false)
            .setView(view)
            .setOnKeyListener(this)
            .setOnDismissListener(this)
            .setPositiveButton(stageActivity.getString(R.string.always)) { _, _ ->
                TrustedDomainManager.addToUserTrustList(domain)
                webAction.grantPermission()
            }
            .setNeutralButton(stageActivity.getString(R.string.cancel)) { _, _ ->
                openDialog(webAccessDialog)
            }
            .create()
    }

    // ---------------- Face name train ----------------

    /**
     * The next step of the face training brick, run in [onDismiss] after the
     * stage has been resumed. Running it from the click listener instead would
     * open the next dialog, or the photo picker, before the old dialog's dismiss
     * resumes the stage. Only one face training dialog is open at a time.
     */
    private var faceTrainNextStep: (() -> Unit)? = null

    private fun thenOnDismiss(step: () -> Unit) {
        faceTrainNextStep = step
    }

    private fun faceTrainBuilder(title: String): AlertDialog.Builder =
        AlertDialog.Builder(ContextThemeWrapper(stageActivity, R.style.Theme_AppCompat_Dialog))
            .setTitle(title)
            .setCancelable(false)
            .setOnKeyListener(this)
            .setOnDismissListener(this)

    private fun createFaceTrainMenuDialog(action: FaceNameTrainAction): Dialog {
        val names = action.personNames()
        val builder = faceTrainBuilder(stageActivity.getString(R.string.face_train_title))
            .setPositiveButton(stageActivity.getString(R.string.face_train_add_new_name)) { _, _ ->
                thenOnDismiss { action.onAddNameChosen() }
            }
            .setNegativeButton(stageActivity.getString(R.string.face_train_done)) { _, _ ->
                thenOnDismiss { action.onDone() }
            }
        if (names.isEmpty()) {
            builder.setMessage(stageActivity.getString(R.string.face_train_no_names))
        } else {
            builder.setItems(names.toTypedArray()) { _, which ->
                thenOnDismiss { action.onPersonChosen(which) }
            }
            builder.setNeutralButton(stageActivity.getString(R.string.face_train_delete)) { _, _ ->
                thenOnDismiss { action.onDeleteChosen() }
            }
        }
        return builder.create()
    }

    private fun createFaceTrainNewNameDialog(action: FaceNameTrainAction): Dialog {
        val input = EditText(stageActivity).apply {
            hint = stageActivity.getString(R.string.face_train_name_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val dialog = faceTrainBuilder(stageActivity.getString(R.string.face_train_new_name_title))
            .setMessage(stageActivity.getString(R.string.face_train_name_subtitle))
            .setView(input)
            // Replaced in the show listener, so an empty name keeps the dialog open.
            .setPositiveButton(stageActivity.getString(R.string.face_train_next), null)
            .setNegativeButton(stageActivity.getString(R.string.face_train_cancel)) { _, _ ->
                thenOnDismiss { action.onNewNameCancelled() }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = stageActivity.getString(R.string.face_train_name_required)
                } else {
                    thenOnDismiss { action.onNewName(name) }
                    dialog.dismiss()
                }
            }
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        return dialog
    }

    private fun createFaceTrainDeleteChoiceDialog(action: FaceNameTrainAction): Dialog =
        faceTrainBuilder(stageActivity.getString(R.string.face_train_delete_choose_title))
            .setItems(action.personNames().toTypedArray()) { _, which ->
                thenOnDismiss { action.onDeleteTargetChosen(which) }
            }
            .setNegativeButton(stageActivity.getString(R.string.face_train_cancel)) { _, _ ->
                thenOnDismiss { action.onDeleteCancelled() }
            }
            .create()

    private fun createFaceTrainDeleteConfirmDialog(action: FaceNameTrainAction, index: Int): Dialog {
        val name = action.personNames().getOrNull(index) ?: ""
        return faceTrainBuilder(stageActivity.getString(R.string.face_train_delete_title))
            .setMessage(stageActivity.getString(R.string.face_train_delete_message, name))
            .setPositiveButton(stageActivity.getString(R.string.face_train_yes)) { _, _ ->
                thenOnDismiss { action.onDeleteConfirmed(index) }
            }
            .setNegativeButton(stageActivity.getString(R.string.face_train_no)) { _, _ ->
                thenOnDismiss { action.onDeleteCancelled() }
            }
            .create()
    }

    /** No buttons: the action closes it when training has finished. */
    private fun createFaceTrainProgressDialog(action: FaceNameTrainAction): Dialog {
        val bar = ProgressBar(stageActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = action.progressMax()
            progress = action.progressValue()
        }
        val padding = (24 * stageActivity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(stageActivity).apply {
            setPadding(padding, 0, padding, 0)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = faceTrainBuilder(stageActivity.getString(R.string.face_train_progress_title))
            .setMessage(action.progressText(stageActivity))
            .setView(container)
            .create()
        dialog.setOnShowListener { action.onProgressDialogShown(dialog, bar) }
        return dialog
    }

    override fun onKey(dialog: DialogInterface, keyCode: Int, event: KeyEvent) =
        (keyCode == KeyEvent.KEYCODE_BACK).also {
            if (it) stageActivity.onBackPressed()
        }

    override fun onDismiss(dialog: DialogInterface) {
        openDialogs.remove(dialog as Dialog)
        // Dismissed because the stage is closing: its listener may already be
        // gone, so resuming it (or opening the next step) would crash.
        if (closingStage || stageActivity.isFinishing || stageActivity.isDestroyed) {
            faceTrainNextStep = null
            return
        }
        StageLifeCycleController.stageResume(stageActivity)
        val step = faceTrainNextStep
        faceTrainNextStep = null
        step?.invoke()
    }
}
