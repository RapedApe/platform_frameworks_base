/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import java.util.ArrayList;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.PieControlPanel;

public class PanelBar extends FrameLayout {
    public static final boolean DEBUG = false;
    public static final String TAG = PanelBar.class.getSimpleName();
    public static final void LOG(String fmt, Object... args) {
        if (!DEBUG) return;
        Slog.v(TAG, String.format(fmt, args));
    }

    public static final int STATE_CLOSED = 0;
    public static final int STATE_OPENING = 1;
    public static final int STATE_OPEN = 2;

    PanelHolder mPanelHolder;
    ArrayList<PanelView> mPanels = new ArrayList<PanelView>();
    PanelView mTouchingPanel;
    private int mState = STATE_CLOSED;
    private boolean mTracking;
    private BaseStatusBar mStatusBar;

    float mPanelExpandedFractionSum;

    public void go(int state) {
        if (DEBUG) LOG("go state: %d -> %d", mState, state);
        mState = state;
    }

    public PanelBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    public void addPanel(PanelView pv) {
        mPanels.add(pv);
        pv.setBar(this);
    }

    public void setStatusBar(BaseStatusBar statusBar) {
        mStatusBar = statusBar;
    }

    public void setPanelHolder(PanelHolder ph) {
        if (ph == null) {
            Slog.e(TAG, "setPanelHolder: null PanelHolder", new Throwable());
            return;
        }
        ph.setBar(this);
        mPanelHolder = ph;
        final int N = ph.getChildCount();
        for (int i=0; i<N; i++) {
            final View v = ph.getChildAt(i);
            if (v != null && v instanceof PanelView) {
                addPanel((PanelView) v);
            }
        }
    }

    /*
     * ]0 < alpha < 1[
     */
    public void setBackgroundAlpha(float alpha) {
        Drawable bg = getBackground();
        if (bg == null)
            return;

        int a = (int) (alpha * 255);
        bg.setAlpha(a);
    }

    public float getBarHeight() {
        return getMeasuredHeight();
    }

    public PanelView selectPanelForTouch(MotionEvent touch) {
        final int N = mPanels.size();
        return mPanels.get((int)(N * touch.getX() / getMeasuredWidth()));
    }

    public boolean panelsEnabled() {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Allow subclasses to implement enable/disable semantics
        if (!panelsEnabled()) return false;

        // figure out which panel needs to be talked to here
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            final PanelView panel = selectPanelForTouch(event);
            if (panel == null) {
                // panel is not there, so we'll eat the gesture
                if (DEBUG) LOG("PanelBar.onTouch: no panel for x=%d, bailing", event.getX());
                mTouchingPanel = null;
                return true;
            }
            boolean enabled = panel.isEnabled();
            if (DEBUG) LOG("PanelBar.onTouch: state=%d ACTION_DOWN: panel %s %s", mState, panel,
                    (enabled ? "" : " (disabled)"));
            if (!enabled) {
                // panel is disabled, so we'll eat the gesture
                mTouchingPanel = null;
                return true;
            }
            startOpeningPanel(panel);
        }
        final boolean result = mTouchingPanel != null
                ? mTouchingPanel.onTouchEvent(event)
                : true;
        return result;
    }

    // called from PanelView when self-expanding, too
    public void startOpeningPanel(PanelView panel) {
        if (DEBUG) LOG("startOpeningPanel: " + panel);
        mTouchingPanel = panel;
        mPanelHolder.setSelectedPanel(mTouchingPanel);
        for (PanelView pv : mPanels) {
            if (pv != panel) {
                pv.collapse();
            }
        }
    }

    public void panelExpansionChanged(PanelView panel, float frac) {
        boolean fullyClosed = true;
        PanelView fullyOpenedPanel = null;
        if (DEBUG) LOG("panelExpansionChanged: start state=%d panel=%s", mState, panel.getName());
        mPanelExpandedFractionSum = 0f;
        for (PanelView pv : mPanels) {
            final boolean visible = pv.getVisibility() == View.VISIBLE;
            // adjust any other panels that may be partially visible
            if (pv.getExpandedHeight() > 0f) {
                if (mState == STATE_CLOSED) {
                    go(STATE_OPENING);
                    onPanelPeeked();
                }
                fullyClosed = false;
                final float thisFrac = pv.getExpandedFraction();
                mPanelExpandedFractionSum += (visible ? thisFrac : 0);
                if (DEBUG) LOG("panelExpansionChanged:  -> %s: f=%.1f", pv.getName(), thisFrac);
                if (panel == pv) {
                    if (thisFrac == 1f) fullyOpenedPanel = panel;
                }
            }
            if (pv.getExpandedHeight() > 0f) {
                if (!visible) pv.setVisibility(View.VISIBLE);
            } else {
                if (visible) pv.setVisibility(View.GONE);
            }
        }
        mPanelExpandedFractionSum /= mPanels.size();
        if (fullyOpenedPanel != null && !mTracking) {
            go(STATE_OPEN);
            onPanelFullyOpened(fullyOpenedPanel);
        } else if (fullyClosed && !mTracking && mState != STATE_CLOSED) {
            go(STATE_CLOSED);
            onAllPanelsCollapsed();
        }

        if (DEBUG) LOG("panelExpansionChanged: end state=%d [%s%s ]", mState,
                (fullyOpenedPanel!=null)?" fullyOpened":"", fullyClosed?" fullyClosed":"");
    }

    public void collapseAllPanels(boolean animate) {
        boolean waiting = false;
        boolean mCHammer = Settings.System.getInt(mContext.getContentResolver(),
                                  Settings.System.CURRENT_UI_MODE, 0) == 1;
        for (PanelView pv : mPanels) {
            if (animate && !pv.isFullyCollapsed()) {
                pv.collapse();
                waiting = true;
            } else {
                pv.setExpandedFraction(0); // just in case
                pv.setVisibility(View.GONE);
            }
        }
        if (DEBUG) LOG("collapseAllPanels: animate=%s waiting=%s", animate, waiting);
        if (!waiting && mState != STATE_CLOSED) {
            // it's possible that nothing animated, so we replicate the termination 
            // conditions of panelExpansionChanged here
            go(STATE_CLOSED);
            onAllPanelsCollapsed();
        }
        if (mCHammer) {
            collapse();
	    }
    }

     protected void collapse() {
        try {
            IStatusBarService sb = IStatusBarService.Stub.asInterface(ServiceManager
                    .getService(Context.STATUS_BAR_SERVICE));
            sb.collapsePanels();
        } catch (RemoteException e) {
        }
    } 

    public void onPanelPeeked() {
        if (DEBUG) LOG("onPanelPeeked");
    }

    public void onAllPanelsCollapsed() {
        if (DEBUG) LOG("onAllPanelsCollapsed");
    }

    public void onPanelFullyOpened(PanelView openPanel) {
        if (DEBUG) LOG("onPanelFullyOpened");
    }

    public void onTrackingStarted(PanelView panel) {
        mTracking = true;
        if (DEBUG && panel != mTouchingPanel) {
            LOG("shouldn't happen: onTrackingStarted(%s) != mTouchingPanel(%s)",
                    panel, mTouchingPanel);
        }
    }

    public void onTrackingStopped(PanelView panel) {
        mTracking = false;
        panelExpansionChanged(panel, panel.getExpandedFraction());
    }
}
