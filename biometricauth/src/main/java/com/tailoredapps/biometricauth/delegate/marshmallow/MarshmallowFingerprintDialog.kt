package com.tailoredapps.biometricauth.delegate.marshmallow

import android.annotation.TargetApi
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.support.annotation.RestrictTo
import android.support.annotation.StyleRes
import android.support.constraint.ConstraintLayout
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.BottomSheetDialog
import android.support.design.widget.BottomSheetDialogFragment
import android.support.v4.content.ContextCompat
import android.support.v4.os.CancellationSignal
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.tailoredapps.biometricauth.BiometricAuthenticationCancelledException
import com.tailoredapps.biometricauth.BiometricAuthenticationException
import com.tailoredapps.biometricauth.R
import com.tailoredapps.biometricauth.delegate.AuthenticationEvent
import io.reactivex.Completable
import io.reactivex.CompletableEmitter
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@TargetApi(23)
@RestrictTo(RestrictTo.Scope.LIBRARY)
class MarshmallowFingerprintDialog : BottomSheetDialogFragment() {

    companion object {
        fun create(title: CharSequence, subtitle: CharSequence?, description: CharSequence?,
                   negativeButtonText: CharSequence,
                   prompt: CharSequence, notRecognizedErrorText: CharSequence,
                   requestId: Int): MarshmallowFingerprintDialog {
            return MarshmallowFingerprintDialog().apply {
                arguments = Bundle().apply {
                    putInt(EXTRA_REQUEST_ID, requestId)
                    putCharSequence(EXTRA_TITLE, title)
                    putCharSequence(EXTRA_SUBTITLE, subtitle)
                    putCharSequence(EXTRA_DESCRIPTION, description)
                    putCharSequence(EXTRA_NEGATIVE, negativeButtonText)
                    putCharSequence(EXTRA_PROMPT, prompt)
                    putCharSequence(EXTRA_NOT_RECOGNIZED, notRecognizedErrorText)
                }
            }
        }

        private const val EXTRA_REQUEST_ID = "req_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_DESCRIPTION = "description"
        private const val EXTRA_NEGATIVE = "negative"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_NOT_RECOGNIZED = "not_recognized"
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var description: TextView
    private lateinit var iconImageView: ImageView
    private lateinit var errorIconImageView: ImageView
    private lateinit var iconTextView: TextView
    private lateinit var cancelButton: Button

    private inline val emitter: CompletableEmitter?
        get() = arguments?.getInt(EXTRA_REQUEST_ID)?.let { MarshmallowBiometricAuth.getEmitter(it) }

    private lateinit var cancellationSignal: CancellationSignal
    private lateinit var authManager: MarshmallowAuthManager

    private val compositeDisposable = CompositeDisposable()

    /**
     * Contains the animation duration in milliseconds. Is set in [onCreateDialog].
     */
    private var animationTime: Long = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cancellationSignal = CancellationSignal()
        authManager = MarshmallowAuthManager(context!!)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = FixedWidthInLandscapeBottomSheetDialog(440f, requireContext(), theme)
        dialog.setContentView(R.layout.dialog_fingerprint)

        val sheetView = dialog.findViewById<View>(R.id.bottom_sheet_container)?.parent as? View
        val behavior = sheetView?.let { BottomSheetBehavior.from(it) }

        animationTime = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()

        title = dialog.findViewById(R.id.title)!!
        subtitle = dialog.findViewById(R.id.subtitle)!!
        description = dialog.findViewById(R.id.description)!!
        iconImageView = dialog.findViewById(R.id.icon)!!
        errorIconImageView = dialog.findViewById(R.id.error_icon)!!
        iconTextView = dialog.findViewById(R.id.icon_text)!!
        cancelButton = dialog.findViewById(R.id.cancel)!!


        arguments?.also { arguments ->
            title.text = arguments.getCharSequence(EXTRA_TITLE)
            subtitle.setTextOrGone(arguments.getCharSequence(EXTRA_SUBTITLE))
            description.setTextOrGone(arguments.getCharSequence(EXTRA_DESCRIPTION))
            cancelButton.text = arguments.getCharSequence(EXTRA_NEGATIVE)
            iconTextView.text = arguments.getCharSequence(EXTRA_PROMPT)
        }


        cancelButton.setOnClickListener { _ ->
            errorResetTimerDisposable?.dispose()
            emitter?.onCancel()
            cancellationSignal.cancel()
            dismissAllowingStateLoss()
        }

        dialog.setOnShowListener { _ ->
            sheetView?.height?.let { height -> behavior?.peekHeight = height }
            authManager.authenticate(cancellationSignal)
                    .observeOn(AndroidSchedulers.mainThread())
                    .delegateErrorOutputToViewOnNext()
                    .filter { event -> event.type == AuthenticationEvent.Type.SUCCESS || event.type == AuthenticationEvent.Type.ERROR }
                    .switchMapSingle { event ->
                        if (event.type == AuthenticationEvent.Type.ERROR) {
                            Single.timer(2, TimeUnit.SECONDS).map { event }
                        } else {
                            Single.just(event)
                        }
                    }
                    .firstElement()
                    .subscribe(
                            { event ->
                                if (event.type == AuthenticationEvent.Type.SUCCESS) {
                                    emitter?.onComplete()
                                } else {
                                    if (event.messageId == 5) {  //"Fingerprint operation cancelled."
                                        emitter?.onCancel()
                                    } else {
                                        emitter?.onError(BiometricAuthenticationException(
                                                errorMessageId = event.messageId ?: 0,
                                                errorString = event.string ?: ""
                                        ))
                                    }
                                }
                                dismissAllowingStateLoss()
                            },
                            { throwable -> emitter?.onError(throwable) }
                    )
                    .addTo(compositeDisposable)
        }

        return dialog
    }

    private fun TextView.setTextOrGone(text: CharSequence?) {
        if (text.isNullOrBlank()) {
            this.text = null
            this.visibility = View.GONE
        } else {
            this.visibility = View.VISIBLE
            this.text = text
        }
    }

    /**
     * Calls [showError] on help/error items respectively with the string emitted within the error
     */
    private fun Flowable<AuthenticationEvent>.delegateErrorOutputToViewOnNext(): Flowable<AuthenticationEvent> {
        return this.doOnNext { event ->
            when (event.type) {
                AuthenticationEvent.Type.ERROR -> {
                    showError(event.string!!)
                }
                AuthenticationEvent.Type.HELP -> {
                    showError(event.string!!)
                }
                AuthenticationEvent.Type.FAILED -> {
                    showError(arguments?.getCharSequence(EXTRA_NOT_RECOGNIZED) ?: "Not recognized")
                }
                AuthenticationEvent.Type.SUCCESS -> {
                }
            }
        }
    }

    /**
     * @return A new [AnimationSet] with a shared [DecelerateInterpolator] and fillAfter enabled.
     */
    private fun getAnimationSet(): AnimationSet {
        return AnimationSet(true).apply {
            interpolator = DecelerateInterpolator()
            fillAfter = true
            isFillEnabled = true
        }
    }

    /**
     * @return A new [RotateAnimation] which rotates for [animationTime] 360 degrees either [forward] or backward.
     */
    private fun getRotateAnimation(forward: Boolean): RotateAnimation {
        return RotateAnimation(
                if (forward) 0f else 360f, if (forward) 360f else 0f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = animationTime
            fillAfter = true
        }
    }

    /**
     * @return a new [AlphaAnimation] which translates from alpha 1 to 0 if [disappearing] is true, or from 0 to 1 if [disappearing] is false.
     */
    private fun getAlphaAnimation(disappearing: Boolean): AlphaAnimation {
        return AlphaAnimation(if (disappearing) 1f else 0f, if (disappearing) 0f else 1f).apply {
            duration = animationTime
            fillAfter = true
        }
    }

    /**
     * Animates the icons:
     * * if [reverse] is false (default):
     *    * Fades the alpha of the fingerprint-icon from 1 to 0
     *    * Fades the alpha of the error-icon from 0 to 1
     *    * Rotates both the fingerprint and error icon 360 degrees
     * * if [reverse] is true:
     *    * Fades the alpha of the fingerprint-icon from 0 to 1
     *    * Fades the alpha of the error-icon from 1 to 0
     *    * Rotates both the fingerprint and error icon -360 degrees
     */
    private fun transitionToError(reverse: Boolean = false) {
        val fingerprintIconAnimationSet = getAnimationSet().apply {
            addAnimation(getRotateAnimation(reverse.not()))
            addAnimation(getAlphaAnimation(reverse.not()))
        }
        iconImageView.startAnimation(fingerprintIconAnimationSet)


        val errorIconAnimationSet = getAnimationSet().apply {
            addAnimation(getRotateAnimation(reverse.not()))
            addAnimation(getAlphaAnimation(reverse))
        }
        errorIconImageView.startAnimation(errorIconAnimationSet)

        if (reverse.not()) {
            errorIconImageView.visibility = View.VISIBLE
        }

        errorIconImageView.visibility = View.VISIBLE
    }

    /**
     * Only rotates the error icon for 360 degrees, which should be done if a new error occurs while
     * a previous error is still shown.
     */
    private fun rotateErrorIcon() {
        val errorIconAnimationSet = getAnimationSet().apply {
            addAnimation(getRotateAnimation(true))
        }
        errorIconImageView.startAnimation(errorIconAnimationSet)
    }

    /**
     * [Disposable] for the timer which starts when an error is shown, and clears the error being
     * shown after the given time.
     */
    private var errorResetTimerDisposable: Disposable? = null

    private fun showError(message: CharSequence) {
        // Check whether there is already an error still showing,
        // as then only the error-icon should be rotated.
        val isErrorStillShowing = errorResetTimerDisposable?.isDisposed?.not() ?: false

        // dispose the errorResetTimer, to not clear any error still shown
        // (or clear the new error just after it has been set)
        errorResetTimerDisposable?.dispose()
        errorResetTimerDisposable = null

        iconTextView.text = message
        iconTextView.setTextColor(ContextCompat.getColor(iconTextView.context, R.color.biometric_icon_text_color_error))

        if (isErrorStillShowing) {
            rotateErrorIcon()
        } else {
            transitionToError()
        }

        errorResetTimerDisposable = Completable.timer(2, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        {
                            //Reset the error message, revert the error animation
                            iconTextView.text = arguments?.getCharSequence(EXTRA_PROMPT) ?: "Touch the fingerprint sensor"
                            iconTextView.setTextColor(ContextCompat.getColor(iconTextView.context, R.color.biometric_icon_text_color))
                            transitionToError(reverse = true)
                        },
                        { Log.i("BiometricAuth", "Timer error", it) }
                )
                // Also add this disposable to the compositeDisposable,
                // as this e.g. is the only one being cleared at onDestroy)
                .addTo(compositeDisposable)
    }

    override fun onCancel(dialog: DialogInterface?) {
        errorResetTimerDisposable?.dispose()
        cancellationSignal.cancel()
        emitter?.onCancel()
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface?) {
        errorResetTimerDisposable?.dispose()
        super.onDismiss(dialog)
    }

    override fun onDestroy() {
        compositeDisposable.clear()
        super.onDestroy()
    }

    private fun CompletableEmitter.onCancel() = this.onError(BiometricAuthenticationCancelledException())


    /**
     * BottomSheetDialog, which sets the view to a fixed width if its shown in landscape mode.
     */
    internal class FixedWidthInLandscapeBottomSheetDialog(
            private val fixedLandscapeWidthInDp: Float,
            context: Context,
            @StyleRes theme: Int
    ) : BottomSheetDialog(context, theme) {

        private inline val Float.dpAsPixel: Int
            get() = (this * (context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)).roundToInt()

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                window?.setLayout(fixedLandscapeWidthInDp.dpAsPixel, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }

//        override fun <T : View?> findViewById(id: Int): T? {
//            return super.findViewById<T>(id) ?: throw KotlinNullPointerException("findViewById($id) is null")
//        }

    }

}