/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.callfiltering;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.Call;
import com.android.server.telecom.Log;
import com.android.server.telecom.Runnable;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.Timeouts;

import java.util.List;

public class IncomingCallFilter implements CallFilterResultCallback {

    public interface CallFilter {
        void startFilterLookup(Call call, CallFilterResultCallback listener);
    }

    private final TelecomSystem.SyncRoot mLock;
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<CallFilter> mFilters;
    private final Call mCall;
    private final CallFilterResultCallback mListener;

    private CallFilteringResult mResult = new CallFilteringResult(
            true, // shouldAllowCall
            false, // shouldReject
            true, // shouldAddToCallLog
            true // shouldShowNotification
    );

    private boolean mIsPending = true;
    private int mNumPendingFilters;

    public IncomingCallFilter(Context context, CallFilterResultCallback listener, Call call,
            TelecomSystem.SyncRoot lock, List<CallFilter> filters) {
        mContext = context;
        mListener = listener;
        mCall = call;
        mLock = lock;
        mFilters = filters;
        mNumPendingFilters = filters.size();
    }

    public void performFiltering() {
        for (CallFilter filter : mFilters) {
            filter.startFilterLookup(mCall, this);
        }
        mHandler.postDelayed(new Runnable("ICF.pFTO") { // performFiltering time-out
            @Override
            public void loggedRun() {
                synchronized (mLock) { // synchronized to prevent a race on mResult
                    if (mIsPending) {
                        Log.i(IncomingCallFilter.this, "Call filtering has timed out.");
                        mListener.onCallFilteringComplete(mCall, mResult);
                        mIsPending = false;
                    }
                }
            }
        }.prepare(), Timeouts.getCallScreeningTimeoutMillis(mContext.getContentResolver()));
    }

    public void onCallFilteringComplete(Call call, CallFilteringResult result) {
        synchronized (mLock) {
            mNumPendingFilters--;
            mResult = result.combine(mResult);
            if (mNumPendingFilters == 0) {
                mHandler.post(new Runnable("ICF.oCFC") {
                    @Override
                    public void loggedRun() {
                        if (mIsPending) {
                            mListener.onCallFilteringComplete(mCall, mResult);
                            mIsPending = false;
                        }
                    }
                }.prepare());
            }
        }
    }

    /**
     * Returns the handler, for testing purposes.
     */
    @VisibleForTesting
    public Handler getHandler() {
        return mHandler;
    }
}
