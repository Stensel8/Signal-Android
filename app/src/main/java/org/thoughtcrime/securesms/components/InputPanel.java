package org.thoughtcrime.securesms.components;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.text.SpannableString;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Interpolator;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DimenRes;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.concurrent.ListenableFuture;
import org.signal.core.util.concurrent.SettableFuture;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.animation.AnimationCompleteListener;
import org.thoughtcrime.securesms.animation.AnimationStartListener;
import org.thoughtcrime.securesms.audio.AudioRecordingHandler;
import org.thoughtcrime.securesms.components.emoji.EmojiEventListener;
import org.thoughtcrime.securesms.components.emoji.EmojiToggle;
import org.thoughtcrime.securesms.components.emoji.MediaKeyboard;
import org.thoughtcrime.securesms.components.voice.VoiceNotePlaybackState;
import org.thoughtcrime.securesms.conversation.ConversationMessage;
import org.thoughtcrime.securesms.conversation.ConversationStickerSuggestionAdapter;
import org.thoughtcrime.securesms.conversation.MessageStyler;
import org.thoughtcrime.securesms.conversation.VoiceNoteDraftView;
import org.thoughtcrime.securesms.database.DraftTable;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.Quote;
import org.thoughtcrime.securesms.database.model.StickerRecord;
import org.thoughtcrime.securesms.keyboard.KeyboardPage;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewRepository;
import org.thoughtcrime.securesms.mms.DecryptableUri;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.MessageRecordUtil;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.AssertedSuccessListener;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class InputPanel extends ConstraintLayout
    implements AudioRecordingHandler,
               KeyboardAwareLinearLayout.OnKeyboardShownListener,
               EmojiEventListener,
               ConversationStickerSuggestionAdapter.EventListener
{

  private static final String TAG = Log.tag(InputPanel.class);

  private static final long QUOTE_REVEAL_DURATION_MILLIS = 150;
  private static final int  FADE_TIME                    = 150;

  private RecyclerView    stickerSuggestion;
  private QuoteView       quoteView;
  private LinkPreviewView linkPreview;
  private EmojiToggle     mediaKeyboard;
  private ComposeText     composeText;
  private ImageButton     quickCameraToggle;
  private ImageButton     quickAudioToggle;
  private AnimatingToggle buttonToggle;
  private SendButton      sendButton;
  private View            recordingContainer;
  private View            recordLockCancel;
  private View            composeContainer;
  private View            editMessageCancel;
  private ImageView       editMessageThumbnail;
  private View            editMessageTitle;
  private FrameLayout     composeTextContainer;

  private MicrophoneRecorderView microphoneRecorderView;
  private SlideToCancel          slideToCancel;
  private RecordTime             recordTime;
  private ValueAnimator          quoteAnimator;
  private ValueAnimator          editMessageAnimator;
  private VoiceNoteDraftView     voiceNoteDraftView;

  private @Nullable Listener listener;
  private           boolean  emojiVisible;

  private boolean hideForMessageRequestState;
  private boolean hideForGroupState;
  private boolean hideForBlockedState;
  private boolean hideForSearch;
  private boolean hideForSelection;

  private ConversationStickerSuggestionAdapter stickerSuggestionAdapter;
  private MessageRecord                        messageToEdit;

  public InputPanel(Context context) {
    super(context);
  }

  public InputPanel(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public InputPanel(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    View quoteDismiss = findViewById(R.id.quote_dismiss_stub);

    this.composeContainer       = findViewById(R.id.compose_bubble);
    this.stickerSuggestion      = findViewById(R.id.input_panel_sticker_suggestion);
    this.quoteView              = findViewById(R.id.quote_view);
    this.linkPreview            = findViewById(R.id.link_preview);
    this.mediaKeyboard          = findViewById(R.id.emoji_toggle);
    this.composeText            = findViewById(R.id.embedded_text_editor);
    this.composeTextContainer   = findViewById(R.id.embedded_text_editor_container);
    this.quickCameraToggle      = findViewById(R.id.quick_camera_toggle);
    this.quickAudioToggle       = findViewById(R.id.quick_audio_toggle);
    this.buttonToggle           = findViewById(R.id.button_toggle);
    this.sendButton             = findViewById(R.id.send_button);
    this.recordingContainer     = findViewById(R.id.recording_container);
    this.recordLockCancel       = findViewById(R.id.record_cancel);
    this.voiceNoteDraftView     = findViewById(R.id.voice_note_draft_view);
    this.slideToCancel          = new SlideToCancel(findViewById(R.id.slide_to_cancel));
    this.microphoneRecorderView = findViewById(R.id.recorder_view);
    this.microphoneRecorderView.setHandler(this);
    this.recordTime             = new RecordTime(findViewById(R.id.record_time),
                                                 findViewById(R.id.microphone),
                                                 TimeUnit.HOURS.toSeconds(1),
                                                 () -> microphoneRecorderView.cancelAction(false));
    this.editMessageCancel      = findViewById(R.id.input_panel_exit_edit_mode);
    this.editMessageTitle       = findViewById(R.id.edit_message_title);
    this.editMessageThumbnail   = findViewById(R.id.edit_message_thumbnail);

    this.recordLockCancel.setOnClickListener(v -> microphoneRecorderView.cancelAction(true));

    mediaKeyboard.setVisibility(View.VISIBLE);
    emojiVisible = true;

    quoteDismiss.setOnClickListener(v -> clearQuote());

    linkPreview.setCloseClickedListener(() -> {
      if (listener != null) {
        listener.onLinkPreviewCanceled();
      }
    });

    stickerSuggestionAdapter = new ConversationStickerSuggestionAdapter(Glide.with(this), this);

    stickerSuggestion.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
    stickerSuggestion.setAdapter(stickerSuggestionAdapter);

    editMessageCancel.setOnClickListener(v -> exitEditMessageMode());

    quickCameraToggle.setVisibility(View.GONE);
  }

  public void setListener(final @NonNull Listener listener) {
    this.listener = listener;

    mediaKeyboard.setOnClickListener(v -> listener.onEmojiToggle());
    voiceNoteDraftView.setListener(listener);

    if (Camera.getNumberOfCameras() > 0) {
      quickCameraToggle.setOnClickListener(v -> listener.onQuickCameraToggleClicked());
      quickCameraToggle.setVisibility(View.VISIBLE);
    } else {
      quickCameraToggle.setVisibility(View.GONE);
    }
  }

  public void setMediaListener(@NonNull MediaListener listener) {
    composeText.setMediaListener(listener);
  }

  public void setQuote(@NonNull RequestManager requestManager,
                       long id,
                       @NonNull Recipient author,
                       @Nullable CharSequence body,
                       @NonNull SlideDeck attachments,
                       @NonNull QuoteModel.Type quoteType)
  {
    this.quoteView.setQuote(requestManager, id, author, body, false, attachments, null, quoteType);
    if (listener != null) {
      this.quoteView.setOnClickListener(v -> listener.onQuoteClicked(id, author.getId()));
    }

    int originalHeight = this.quoteView.getVisibility() == VISIBLE ? this.quoteView.getMeasuredHeight()
                                                                   : 0;

    this.quoteView.setVisibility(VISIBLE);

    int maxWidth = composeContainer.getWidth();
    if (quoteView.getLayoutParams() instanceof MarginLayoutParams) {
      MarginLayoutParams layoutParams = (MarginLayoutParams) quoteView.getLayoutParams();
      maxWidth -= layoutParams.leftMargin + layoutParams.rightMargin;
    }
    this.quoteView.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), 0);

    if (quoteAnimator != null) {
      quoteAnimator.cancel();
    }

    quoteAnimator = createHeightAnimator(quoteView, originalHeight, this.quoteView.getMeasuredHeight(), null);

    quoteAnimator.start();

    if (this.linkPreview.getVisibility() == View.VISIBLE) {
      int cornerRadius = readDimen(R.dimen.message_corner_collapse_radius);
      this.linkPreview.setCorners(cornerRadius, cornerRadius);
    }

    if (listener != null) {
      listener.onQuoteChanged(id, author.getId());
    }
  }

  public void clearQuote() {
    if (quoteAnimator != null) {
      quoteAnimator.cancel();
    }

    quoteAnimator = createHeightAnimator(quoteView, quoteView.getMeasuredHeight(), 0, new AnimationCompleteListener() {
      @Override
      public void onAnimationEnd(Animator animation) {
        quoteView.dismiss();

        if (linkPreview.getVisibility() == View.VISIBLE) {
          int cornerRadius = readDimen(R.dimen.message_corner_radius);
          linkPreview.setCorners(cornerRadius, cornerRadius);
        }
      }
    });

    quoteAnimator.start();

    if (listener != null) {
      listener.onQuoteCleared();
    }
  }

  private static ValueAnimator createHeightAnimator(@NonNull View view,
                                                    int originalHeight,
                                                    int finalHeight,
                                                    @Nullable AnimationCompleteListener onAnimationComplete)
  {
    ValueAnimator animator = ValueAnimator.ofInt(originalHeight, finalHeight)
                                          .setDuration(QUOTE_REVEAL_DURATION_MILLIS);

    animator.addUpdateListener(animation -> {
      ViewGroup.LayoutParams params = view.getLayoutParams();
      params.height = (int) animation.getAnimatedValue();
      view.setLayoutParams(params);
    });

    if (onAnimationComplete != null) {
      animator.addListener(onAnimationComplete);
    }

    return animator;
  }

  public boolean hasSaveableContent() {
    return getQuote().isPresent() || voiceNoteDraftView.getDraft() != null;
  }

  public Optional<QuoteModel> getQuote() {
    if (quoteView.getQuoteId() > 0 && quoteView.getVisibility() == View.VISIBLE) {
      return Optional.of(new QuoteModel(quoteView.getQuoteId(),
                                        quoteView.getAuthor().getId(),
                                        quoteView.getBody().toString(),
                                        false,
                                        quoteView.getAttachments(),
                                        quoteView.getMentions(),
                                        quoteView.getQuoteType(),
                                        quoteView.getBodyRanges()));
    } else {
      return Optional.empty();
    }
  }

  public boolean hasLinkPreview() {
    return linkPreview.getVisibility() == View.VISIBLE;
  }

  public void setLinkPreviewLoading() {
    this.linkPreview.setVisibility(View.VISIBLE);
    this.linkPreview.setLoading();
  }

  public void setLinkPreviewNoPreview(@Nullable LinkPreviewRepository.Error customError) {
    this.linkPreview.setVisibility(View.VISIBLE);
    this.linkPreview.setNoPreview(customError);
  }

  public void setLinkPreview(@NonNull RequestManager requestManager, @NonNull Optional<LinkPreview> preview) {
    if (preview.isPresent()) {
      this.linkPreview.setVisibility(View.VISIBLE);
      this.linkPreview.setLinkPreview(requestManager, preview.get(), true);
    } else {
      this.linkPreview.setVisibility(View.GONE);
    }

    int cornerRadius = quoteView.getVisibility() == VISIBLE ? readDimen(R.dimen.message_corner_collapse_radius)
                                                            : readDimen(R.dimen.message_corner_radius);

    this.linkPreview.setCorners(cornerRadius, cornerRadius);
  }

  public void clickOnComposeInput() {
    composeText.performClick();
  }

  public void setMediaKeyboard(@NonNull MediaKeyboard mediaKeyboard) {
    this.mediaKeyboard.attach(mediaKeyboard);
  }

  public void setStickerSuggestions(@NonNull List<StickerRecord> stickers) {
    stickerSuggestion.setVisibility(stickers.isEmpty() ? View.GONE : View.VISIBLE);
    stickerSuggestionAdapter.setStickers(stickers);
  }

  public void showMediaKeyboardToggle(boolean show) {
    emojiVisible = show;
    mediaKeyboard.setVisibility(show ? View.VISIBLE : GONE);
  }

  public void setMediaKeyboardToggleMode(@NonNull KeyboardPage page) {
    mediaKeyboard.setStickerMode(page);
  }

  public boolean isStickerMode() {
    return mediaKeyboard.isStickerMode();
  }

  public View getMediaKeyboardToggleAnchorView() {
    return mediaKeyboard;
  }

  public MediaKeyboard.MediaKeyboardListener getMediaKeyboardListener() {
    return mediaKeyboard;
  }

  public void setWallpaperEnabled(boolean enabled) {
    final int iconTint;
    final int textColor;
    final int textHintColor;

    if (enabled) {
      iconTint = getContext().getResources().getColor(R.color.signal_colorOnSurface);
      textColor = getContext().getResources().getColor(R.color.signal_colorOnSurface);
      textHintColor = getContext().getResources().getColor(R.color.signal_colorOnSurfaceVariant);

      setBackground(null);
      composeContainer.setBackground(Objects.requireNonNull(ContextCompat.getDrawable(getContext(), R.drawable.compose_background_wallpaper)));
      quickAudioToggle.setColorFilter(iconTint);
      quickCameraToggle.setColorFilter(iconTint);
    } else {
      iconTint = getContext().getResources().getColor(R.color.signal_colorOnSurface);
      textColor = getContext().getResources().getColor(R.color.signal_colorOnSurface);
      textHintColor = getContext().getResources().getColor(R.color.signal_colorOnSurfaceVariant);

      setBackground(new ColorDrawable(getContext().getResources().getColor(R.color.signal_colorSurface)));
      composeContainer.setBackground(Objects.requireNonNull(ContextCompat.getDrawable(getContext(), R.drawable.compose_background)));
    }

    mediaKeyboard.setColorFilter(iconTint);
    quickAudioToggle.setColorFilter(iconTint);
    quickCameraToggle.setColorFilter(iconTint);
    composeText.setTextColor(textColor);
    composeText.setHintTextColor(textHintColor);
    quoteView.setWallpaperEnabled(enabled);
  }

  public void enterEditModeIfPossible(@NonNull RequestManager requestManager, @NonNull ConversationMessage conversationMessageToEdit, boolean fromDraft, boolean clearQuote) {
    String currentText = composeText.getText() == null ? "" : composeText.getText().toString();
    if ((messageToEdit == null && currentText.isEmpty()) || (messageToEdit != null && currentText.equals(messageToEdit.getBody()))) {
      enterEditMessageMode(requestManager, conversationMessageToEdit, fromDraft, clearQuote);
    } else {
      AlertDialog.Builder builder = new MaterialAlertDialogBuilder(getContext());
      builder.setTitle(R.string.InputPanel__discard_draft);
      builder.setMessage(R.string.InputPanel__this_action_cant_be_undone);
      builder.setPositiveButton(R.string.InputPanel__discard, (dialog, which) -> enterEditMessageMode(requestManager, conversationMessageToEdit, fromDraft, clearQuote));
      builder.setNegativeButton(android.R.string.cancel, null);
      builder.show();
    }
}

  public void enterEditMessageMode(@NonNull RequestManager requestManager, @NonNull ConversationMessage conversationMessageToEdit, boolean fromDraft, boolean clearQuote) {
    boolean fromEditMessageMode = inEditMessageMode();
    int originalHeight          = composeTextContainer.getMeasuredHeight();
    SpannableString textToEdit  = conversationMessageToEdit.getDisplayBody(getContext());

    if (!fromDraft) {
      MessageStyler.convertSpoilersToComposeMode(textToEdit);
      composeText.setText(textToEdit);
      composeText.setSelection(textToEdit.length());
    }

    Quote quote = MessageRecordUtil.getQuote(conversationMessageToEdit.getMessageRecord());
    if (quote == null || clearQuote) {
      clearQuote();
    } else {
      setQuote(requestManager, quote.getId(), Recipient.resolved(quote.getAuthor()), quote.getDisplayText(), quote.getAttachment(), quote.getQuoteType());
    }

    this.messageToEdit = conversationMessageToEdit.getMessageRecord();

    updateEditModeUi();
    updateEditModeThumbnail(requestManager);

    int maxWidth = composeContainer.getWidth() - mediaKeyboard.getWidth();
    if (!fromEditMessageMode) {
      maxWidth -= editMessageCancel.getWidth();
      if (editMessageCancel.getLayoutParams() instanceof MarginLayoutParams) {
        MarginLayoutParams layoutParams = (MarginLayoutParams) editMessageCancel.getLayoutParams();
        maxWidth -= layoutParams.leftMargin;
      }
    }
    composeTextContainer.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
    int finalHeight = composeTextContainer.getMeasuredHeight();

    if (editMessageAnimator != null) {
      editMessageAnimator.cancel();
    }
    editMessageAnimator = createHeightAnimator(composeTextContainer, originalHeight, finalHeight, new AnimationCompleteListener() {
      @Override
      public void onAnimationEnd(Animator animation) {
        ViewGroup.LayoutParams params = composeTextContainer.getLayoutParams();
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        composeTextContainer.setLayoutParams(params);
      }
    });
    editMessageAnimator.start();
  }

  private void updateEditModeThumbnail(@NonNull RequestManager requestManager) {
    if (messageToEdit instanceof MmsMessageRecord) {
      MmsMessageRecord mediaEditMessage = (MmsMessageRecord) messageToEdit;
      SlideDeck        slideDeck        = mediaEditMessage.getSlideDeck();
      Slide            imageVideoSlide  = slideDeck.getSlides().stream().filter(s -> s.hasImage() || s.hasVideo() || s.hasSticker()).findFirst().orElse(null);

      if (imageVideoSlide != null && imageVideoSlide.getUri() != null) {
        editMessageThumbnail.setVisibility(VISIBLE);
        requestManager.load(new DecryptableUri(imageVideoSlide.getUri()))
                     .centerCrop()
                     .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                     .into(editMessageThumbnail);
      } else {
        editMessageThumbnail.setVisibility(View.GONE);
      }
    } else {
      editMessageThumbnail.setVisibility(View.GONE);
    }
  }

  public void exitEditMessageMode() {
    int originalHeight = composeTextContainer.getMeasuredHeight();
    if (messageToEdit != null) {
      composeText.setText("");
      messageToEdit = null;
      quoteView.setMessageType(QuoteView.MessageType.PREVIEW);
      clearQuote();
    }
    updateEditModeUi();

    composeTextContainer.measure(0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

    if (editMessageAnimator != null) {
     editMessageAnimator.cancel();
    }
    editMessageAnimator = createHeightAnimator(composeTextContainer, originalHeight, composeTextContainer.getMeasuredHeight(), new AnimationCompleteListener() {
      @Override
      public void onAnimationEnd(Animator animation) {
        ViewGroup.LayoutParams params = composeTextContainer.getLayoutParams();
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        composeTextContainer.setLayoutParams(params);
      }
    });
    editMessageAnimator.start();
  }

  private void updateEditModeUi() {
    if (inEditMessageMode()) {
      ViewUtil.focusAndShowKeyboard(composeText);
      editMessageTitle.setVisibility(View.VISIBLE);
      editMessageThumbnail.setVisibility(View.VISIBLE);
      editMessageCancel.setVisibility(View.VISIBLE);
      if (listener != null) {
        listener.onEnterEditMode();
      }
    } else {
      editMessageTitle.setVisibility(View.GONE);
      editMessageThumbnail.setVisibility(View.GONE);
      editMessageCancel.setVisibility(View.GONE);
      if (listener != null) {
        listener.onExitEditMode();
      }
    }
  }

  public boolean inEditMessageMode() {
    return messageToEdit != null;
  }

  public void setHideForMessageRequestState(boolean hideForMessageRequestState) {
    this.hideForMessageRequestState = hideForMessageRequestState;
    updateVisibility();
  }

  public void setHideForGroupState(boolean hideForGroupState) {
    this.hideForGroupState = hideForGroupState;
    updateVisibility();
  }

  public void setHideForBlockedState(boolean hideForBlockedState) {
    this.hideForBlockedState = hideForBlockedState;
    updateVisibility();
  }

  public void setHideForSearch(boolean hideForSearch) {
    this.hideForSearch = hideForSearch;
    updateVisibility();
  }

  public void setHideForSelection(boolean hideForSelection) {
    this.hideForSelection = hideForSelection;
    updateVisibility();
  }

  @Override
  public void onRecordPermissionRequired() {
    if (listener != null) listener.onRecorderPermissionRequired();
  }

  @Override
  public void onRecorderAlreadyInUse() {
    if (listener != null) listener.onRecorderAlreadyInUse();
  }

  @Override
  public void onRecordPressed() {
    if (listener != null) listener.onRecorderStarted();
    recordTime.display();
    slideToCancel.display();

    if (emojiVisible) {
      fadeOut(mediaKeyboard);
    }

    fadeOut(composeText);
    fadeOut(quickCameraToggle);
    fadeOut(quickAudioToggle);
    fadeOut(buttonToggle);
  }

  @Override
  public void onRecordReleased() {
    long elapsedTime = onRecordHideEvent();

    if (listener != null) {
      Log.d(TAG, "Elapsed time: " + elapsedTime);
      if (elapsedTime > 1000) {
        listener.onRecorderFinished();
      } else {
        Toast.makeText(getContext(), R.string.InputPanel_tap_and_hold_to_record_a_voice_message_release_to_send, Toast.LENGTH_LONG).show();
        listener.onRecorderCanceled(true);
      }
    }
  }

  @Override
  public void onRecordMoved(float offsetX, float absoluteX) {
    slideToCancel.moveTo(offsetX);

    float position  = absoluteX / recordingContainer.getWidth();

    if (ViewUtil.isLtr(this) && position <= 0.5 ||
        ViewUtil.isRtl(this) && position >= 0.6)
    {
      this.microphoneRecorderView.cancelAction(true);
    }
  }

  @Override
  public void onRecordCanceled(boolean byUser) {
    Log.d(TAG, "Recording canceled byUser=" + byUser);
    onRecordHideEvent();
    if (listener != null) listener.onRecorderCanceled(byUser);
  }

  @Override
  public void onRecordLocked() {
    slideToCancel.hide();
    recordLockCancel.setVisibility(View.VISIBLE);
    fadeIn(buttonToggle);
    if (listener != null) listener.onRecorderLocked();
  }

  @Override
  public void onRecordSaved() {
    Log.d(TAG, "Recording saved");
    onRecordHideEvent();
    if (listener != null) listener.onRecorderSaveDraft();
  }

  public void onPause() {
    this.microphoneRecorderView.cancelAction(false);
  }

  public void onSaveRecordDraft() {
    this.microphoneRecorderView.saveAction();
  }

  public @NonNull Observer<VoiceNotePlaybackState> getPlaybackStateObserver() {
    return voiceNoteDraftView.getPlaybackStateObserver();
  }

  public void setEnabled(boolean enabled) {
    composeText.setEnabled(enabled);
    mediaKeyboard.setEnabled(enabled);
    quickAudioToggle.setEnabled(enabled);
    quickCameraToggle.setEnabled(enabled);
  }

  private long onRecordHideEvent() {
    recordLockCancel.setVisibility(View.GONE);

    ListenableFuture<Void> future      = slideToCancel.hide();
    long                   elapsedTime = recordTime.hide();

    future.addListener(new AssertedSuccessListener<Void>() {
      @Override
      public void onSuccess(Void result) {
        if (voiceNoteDraftView.getDraft() == null) {
          fadeInNormalComposeViews();
        }
      }
    });

    return elapsedTime;
  }

  @Override
  public void onKeyboardShown() {
    mediaKeyboard.setToMedia();
  }

  public void setToIme() {
    mediaKeyboard.setToIme();
  }

  @Override
  public void onKeyEvent(KeyEvent keyEvent) {
    composeText.dispatchKeyEvent(keyEvent);
  }

  @Override
  public void onEmojiSelected(String emoji) {
    composeText.insertEmoji(emoji);
  }

  @Override
  public void onStickerSuggestionClicked(@NonNull StickerRecord sticker) {
    if (listener != null) {
      listener.onStickerSuggestionSelected(sticker);
    }
  }

  private int readDimen(@DimenRes int dimenRes) {
    return getResources().getDimensionPixelSize(dimenRes);
  }

  public boolean isRecordingInLockedMode() {
    return microphoneRecorderView.isRecordingLocked();
  }

  public void releaseRecordingLockAndSend() {
    microphoneRecorderView.unlockAction();
  }

  public void setVoiceNoteDraft(@Nullable DraftTable.Draft voiceNoteDraft) {
    if (voiceNoteDraft != null) {
      voiceNoteDraftView.setDraft(voiceNoteDraft);
      voiceNoteDraftView.setVisibility(VISIBLE);
      hideNormalComposeViews();
      fadeIn(buttonToggle);
      buttonToggle.displayQuick(sendButton);
    } else {
      voiceNoteDraftView.clearDraft();
      ViewUtil.fadeOut(voiceNoteDraftView, FADE_TIME);
      fadeInNormalComposeViews();
    }
  }

  public @Nullable DraftTable.Draft getVoiceNoteDraft() {
    return voiceNoteDraftView.getDraft();
  }

  private void hideNormalComposeViews() {
    if (emojiVisible) {
      mediaKeyboard.animate().cancel();
      mediaKeyboard.setAlpha(0f);
    }

    for (View view : Arrays.asList(composeText, quickCameraToggle, quickAudioToggle)) {
      view.animate().cancel();
      view.setAlpha(0f);
    }
  }

  private void fadeInNormalComposeViews() {
    if (emojiVisible) {
      fadeIn(mediaKeyboard);
    }

    fadeIn(composeText);
    fadeIn(quickCameraToggle);
    fadeIn(quickAudioToggle);
    fadeIn(buttonToggle);
  }

  private void fadeIn(@NonNull View v) {
    v.animate()
     .setListener(new AnimationStartListener() {
       @Override
       public void onAnimationStart(@NonNull Animator animation) {
         v.setVisibility(View.VISIBLE);
       }

       @Override
       public void onAnimationCancel(@NonNull Animator animation) {
         v.setVisibility(View.INVISIBLE);
       }
     })
     .alpha(1)
     .setDuration(FADE_TIME)
     .start();
  }

  private void fadeOut(@NonNull View v) {
    v.animate()
     .setListener(new AnimationCompleteListener() {
       @Override
       public void onAnimationEnd(Animator animation) {
         v.setVisibility(View.INVISIBLE);
       }

       @Override
       public void onAnimationCancel(Animator animation) {
         v.setVisibility(View.VISIBLE);
       }
     })
     .alpha(0)
     .setDuration(FADE_TIME)
     .start();
  }

  private void updateVisibility() {
    if (isHidden()) {
      setVisibility(GONE);
    } else {
      setVisibility(VISIBLE);
    }
  }

  public boolean isHidden() {
    return hideForGroupState || hideForBlockedState || hideForSearch || hideForSelection || hideForMessageRequestState;
  }

  public @Nullable MessageRecord getEditMessage() {
    return messageToEdit;
  }
  public @Nullable MessageId getEditMessageId() {
    if (messageToEdit == null) {
      return null;
    }
    return new MessageId(messageToEdit.getId());
  }

  public interface Listener extends VoiceNoteDraftView.Listener {
    void onRecorderStarted();
    void onRecorderLocked();
    void onRecorderSaveDraft();
    void onRecorderFinished();
    void onRecorderCanceled(boolean byUser);
    void onRecorderPermissionRequired();
    void onRecorderAlreadyInUse();
    void onEmojiToggle();
    void onLinkPreviewCanceled();
    void onStickerSuggestionSelected(@NonNull StickerRecord sticker);
    void onQuoteChanged(long id, @NonNull RecipientId author);
    void onQuoteCleared();
    void onQuoteClicked(long quoteId, RecipientId authorId);
    void onEnterEditMode();
    void onExitEditMode();
    void onQuickCameraToggleClicked();
  }

  private static class SlideToCancel {

    private final View slideToCancelView;

    SlideToCancel(View slideToCancelView) {
      this.slideToCancelView = slideToCancelView;
    }

    public void display() {
      ViewUtil.fadeIn(this.slideToCancelView, FADE_TIME);
    }

    public ListenableFuture<Void> hide() {
      final SettableFuture<Void> future = new SettableFuture<>();

      AnimationSet animation = new AnimationSet(true);
      animation.addAnimation(new TranslateAnimation(Animation.ABSOLUTE, slideToCancelView.getTranslationX(),
                                                    Animation.ABSOLUTE, 0,
                                                    Animation.RELATIVE_TO_SELF, 0,
                                                    Animation.RELATIVE_TO_SELF, 0));
      animation.addAnimation(new AlphaAnimation(1, 0));

      animation.setDuration(MicrophoneRecorderView.ANIMATION_DURATION);
      animation.setFillBefore(true);
      animation.setFillAfter(false);

      slideToCancelView.postDelayed(() -> future.set(null), MicrophoneRecorderView.ANIMATION_DURATION);
      slideToCancelView.setVisibility(View.GONE);
      slideToCancelView.startAnimation(animation);

      return future;
    }

    void moveTo(float offset) {
      Animation animation = new TranslateAnimation(Animation.ABSOLUTE, offset,
                                                   Animation.ABSOLUTE, offset,
                                                   Animation.RELATIVE_TO_SELF, 0,
                                                   Animation.RELATIVE_TO_SELF, 0);

      animation.setDuration(0);
      animation.setFillAfter(true);
      animation.setFillBefore(true);

      slideToCancelView.startAnimation(animation);
    }
  }

  private static class RecordTime implements Runnable {

    private final @NonNull TextView recordTimeView;
    private final @NonNull View     microphone;
    private final @NonNull Runnable onLimitHit;
    private final          long     limitSeconds;
    private                long     startTime;

    private RecordTime(@NonNull TextView recordTimeView, @NonNull View microphone, long limitSeconds, @NonNull Runnable onLimitHit) {
      this.recordTimeView = recordTimeView;
      this.microphone     = microphone;
      this.limitSeconds   = limitSeconds;
      this.onLimitHit     = onLimitHit;
    }

    @MainThread
    public void display() {
      this.startTime = System.currentTimeMillis();
      this.recordTimeView.setText(DateUtils.formatElapsedTime(0));
      ViewUtil.fadeIn(this.recordTimeView, FADE_TIME);
      ThreadUtil.runOnMainDelayed(this, TimeUnit.SECONDS.toMillis(1));
      microphone.setVisibility(View.VISIBLE);
      microphone.startAnimation(pulseAnimation());
    }

    @MainThread
    public long hide() {
      long elapsedTime = System.currentTimeMillis() - startTime;
      this.startTime = 0;
      ViewUtil.fadeOut(this.recordTimeView, FADE_TIME, View.INVISIBLE);
      microphone.clearAnimation();
      ViewUtil.fadeOut(this.microphone, FADE_TIME, View.INVISIBLE);
      return elapsedTime;
    }

    @Override
    @MainThread
    public void run() {
      long localStartTime = startTime;
      if (localStartTime > 0) {
        long elapsedTime = System.currentTimeMillis() - localStartTime;
        long elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedTime);
        if (elapsedSeconds >= limitSeconds) {
          onLimitHit.run();
        } else {
          recordTimeView.setText(DateUtils.formatElapsedTime(elapsedSeconds));
          ThreadUtil.runOnMainDelayed(this, TimeUnit.SECONDS.toMillis(1));
        }
      }
    }

    private static Animation pulseAnimation() {
      AlphaAnimation animation = new AlphaAnimation(0, 1);

      animation.setInterpolator(pulseInterpolator());
      animation.setRepeatCount(Animation.INFINITE);
      animation.setDuration(1000);

      return animation;
    }

    private static Interpolator pulseInterpolator() {
      return input -> {
        input *= 5;
        if (input > 1) {
          input = 4 - input;
        }
        return Math.max(0, Math.min(1, input));
      };
    }
  }

  public interface MediaListener {
    void onMediaSelected(@NonNull Uri uri, String contentType);
  }
}
