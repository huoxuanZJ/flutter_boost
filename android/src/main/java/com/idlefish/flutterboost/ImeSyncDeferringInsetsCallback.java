// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.idlefish.flutterboost;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;

import java.util.List;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

// Loosely based off of
// https://github.com/android/user-interface-samples/blob/master/WindowInsetsAnimation/app/src/main/java/com/google/android/samples/insetsanimation/RootViewDeferringInsetsCallback.kt
//
// When the IME is shown or hidden, it immediately sends an onApplyWindowInsets call
// with the final state of the IME. This initial call disrupts the animation, which
// causes a flicker in the beginning.
//
// To fix this, this class extends WindowInsetsAnimation.Callback and implements
// OnApplyWindowInsetsListener. We capture and defer the initial call to
// onApplyWindowInsets while the animation completes. When the animation
// finishes, we can then release the call by invoking it in the onEnd callback
//
// The WindowInsetsAnimation.Callback extension forwards the new state of the
// IME inset from onProgress() to the framework. We also make use of the
// onStart callback to detect which calls to onApplyWindowInsets would
// interrupt the animation and defer it.
//
// By implementing OnApplyWindowInsetsListener, we are able to capture Android's
// attempts to call the FlutterView's onApplyWindowInsets. When a call to onStart
// occurs, we can mark any non-animation calls to onApplyWindowInsets() that
// occurs between prepare and start as deferred by using this class' wrapper
// implementation to cache the WindowInsets passed in and turn the current call into
// a no-op. When onEnd indicates the end of the animation, the deferred call is
// dispatched again, this time avoiding any flicker since the animation is now
// complete.
@VisibleForTesting
@TargetApi(30)
@RequiresApi(30)
@SuppressLint({"NewApi", "Override"})
@Keep
class ImeSyncDeferringInsetsCallback extends WindowInsetsAnimation.Callback
    implements View.OnApplyWindowInsetsListener {
  private int overlayInsetTypes;
  private int deferredInsetTypes;

  private View view;
  private WindowInsets lastWindowInsets;
  // True when an animation that matches deferredInsetTypes is active.
  //
  // While this is active, this class will capture the initial window inset
  // sent into lastWindowInsets by flagging needsSave to true, and will hold
  // onto the intitial inset until the animation is completed, when it will
  // re-dispatch the inset change.
  private boolean animating = false;
  // When an animation begins, android sends a WindowInset with the final
  // state of the animation. When needsSave is true, we know to capture this
  // initial WindowInset.
  private boolean needsSave = false;

  ImeSyncDeferringInsetsCallback(
      @NonNull View view, int overlayInsetTypes, int deferredInsetTypes) {
    super(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE);
    this.overlayInsetTypes = overlayInsetTypes;
    this.deferredInsetTypes = deferredInsetTypes;
    this.view = view;
  }

  // Add this object's event listeners to its view.
  void install() {
    view.setWindowInsetsAnimationCallback(this);
    view.setOnApplyWindowInsetsListener(this);
  }

  // Remove this object's event listeners from its view.
  void remove() {
    view.setWindowInsetsAnimationCallback(null);
    view.setOnApplyWindowInsetsListener(null);
  }

  @Override
  public WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
    this.view = view;
    if (needsSave) {
      // Store the view and insets for us in onEnd() below. This captured inset
      // is not part of the animation and instead, represents the final state
      // of the inset after the animation is completed. Thus, we defer the processing
      // of this WindowInset until the animation completes.
      lastWindowInsets = windowInsets;
      needsSave = false;
    }
    if (animating) {
      // While animation is running, we consume the insets to prevent disrupting
      // the animation, which skips this implementation and calls the view's
      // onApplyWindowInsets directly to avoid being consumed here.
      return WindowInsets.CONSUMED;
    }

    // If no animation is happening, pass the insets on to the view's own
    // inset handling.
    return view.onApplyWindowInsets(windowInsets);
  }

  @Override
  public void onPrepare(WindowInsetsAnimation animation) {
    if ((animation.getTypeMask() & deferredInsetTypes) != 0) {
      animating = true;
      needsSave = true;
    }
  }

  @Override
  public WindowInsets onProgress(
      WindowInsets insets, List<WindowInsetsAnimation> runningAnimations) {
    if (!animating || needsSave) {
      return insets;
    }
    boolean matching = false;
    for (WindowInsetsAnimation animation : runningAnimations) {
      if ((animation.getTypeMask() & deferredInsetTypes) != 0) {
        matching = true;
        continue;
      }
    }
    if (!matching) {
      return insets;
    }
    WindowInsets.Builder builder = new WindowInsets.Builder(lastWindowInsets);
    // Overlay the ime-only insets with the full insets.
    //
    // The IME insets passed in by onProgress assumes that the entire animation
    // occurs above any present navigation and status bars. This causes the
    // IME inset to be too large for the animation. To remedy this, we merge the
    // IME inset with other insets present via a subtract + reLu, which causes the
    // IME inset to be overlaid with any bars present.
    Insets newImeInsets =
        Insets.of(
            0,
            0,
            0,
            Math.max(
                insets.getInsets(deferredInsetTypes).bottom
                    - insets.getInsets(overlayInsetTypes).bottom,
                0));
    builder.setInsets(deferredInsetTypes, newImeInsets);
    // Directly call onApplyWindowInsets of the view as we do not want to pass through
    // the onApplyWindowInsets defined in this class, which would consume the insets
    // as if they were a non-animation inset change and cache it for re-dispatch in
    // onEnd instead.
    view.onApplyWindowInsets(builder.build());
    return insets;
  }

  @Override
  public void onEnd(WindowInsetsAnimation animation) {
    if (animating && (animation.getTypeMask() & deferredInsetTypes) != 0) {
      // If we deferred the IME insets and an IME animation has finished, we need to reset
      // the flags
      animating = false;

      // And finally dispatch the deferred insets to the view now.
      // Ideally we would just call view.requestApplyInsets() and let the normal dispatch
      // cycle happen, but this happens too late resulting in a visual flicker.
      // Instead we manually dispatch the most recent WindowInsets to the view.
      if (lastWindowInsets != null && view != null) {
        view.dispatchApplyWindowInsets(lastWindowInsets);
      }
    }
  }
}
