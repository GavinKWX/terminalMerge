package com.sc.mf919.java.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.text.Html;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import com.sc.mf919.kotlin.activity.AttendActivity;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import android.widget.Toast;

import com.sc.mf919.kotlin.activity.ProgressDialogFragment;

import java.lang.ref.WeakReference;

import com.morefun.yapi.device.printer.MulPrintStrEntity;
import com.morefun.yapi.device.printer.OnPrintListener;
import com.morefun.yapi.device.printer.PrinterConfig;
import com.sc.mf919.R;
import com.sc.mf919.java.device.DeviceHelper;
import com.sc.mf919.kotlin.helper_common.TmsHelper;

import java.util.List;
import java.util.Objects;

import enums.EnumLogFileName;
import helpers.HelperCommon;
import helpers.HelperLog;

public class ActivityBase extends AppCompatActivity
{

    public static final int progress_bar_type = 0;
    protected ProgressDialog pDialog;
    protected AlertDialog alertDialog;
    protected AlertDialog alertDialog_1;
    private onAlertDialogListener mListener;
    protected String pDTitle;
    protected String pDMsg;
    protected String terminalPIN;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
    }

    public void passwordAlertDialog(int id,String expectedResult,onAlertDialogListener _mListener)
    {
        mListener=_mListener;
        DialogInterface.OnKeyListener keyListener = new DialogInterface.OnKeyListener()
        {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent KEvent)
            {
                return keyCode == KeyEvent.KEYCODE_HOME;
            }
        };
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setOnKeyListener(keyListener);
        LayoutInflater inflater = this.getLayoutInflater();
        @SuppressLint("InflateParams") View dialogView = inflater.inflate(R.layout.actvivty_passwordlock, null);
        EditText edTx = dialogView.findViewById(R.id.passwordString);
        edTx.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent)
            {
                if (actionId == EditorInfo.IME_ACTION_SEND)
                {
                    mListener.onResult(id,true,expectedResult.equals(edTx.getText().toString()));
                    edTx.setText("");
                    return true;
                }
                return false;
            }
        });
        TextView position = dialogView.findViewById(R.id.positionBtn);
        position.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view)
            {
                mListener.onResult(id,true,expectedResult.equals(edTx.getText().toString()));
                edTx.setText("");
            }
        });
        TextView negative = dialogView.findViewById(R.id.negativeBtn);
        negative.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                mListener.onResult(id,false,expectedResult.equals(edTx.getText().toString()));
                alertDialog.dismiss();
            }
        });
        alertDialogBuilder.setView(dialogView);
        alertDialogBuilder.setCancelable(true);
        alertDialog = alertDialogBuilder.create();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(Objects.requireNonNull(alertDialog.getWindow()).getAttributes());
        lp.width = 600;
        alertDialog.getWindow().setAttributes(lp);
        alertDialog.show();
    }


    // Always POSTED (never inline) so showNow's commitNow can never run inside
    // an executing FragmentManager transaction. One FIFO queue keeps
    // show/update/hide ordering identical for all callers.
    private final Handler progressHandler = new Handler(Looper.getMainLooper());

    public void showProgress(final String title, final String msg)
    {
        progressHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                // A manually-constructed helper instance (never attached, stays INITIALIZED)
                // has no FragmentManager host — showNow on it throws IllegalStateException
                if (getLifecycle().getCurrentState() == Lifecycle.State.INITIALIZED) return;
                if (isFinishing() || isDestroyed()) return;
                FragmentManager fm = getSupportFragmentManager();
                if (fm.isStateSaved() || fm.isDestroyed()) return;
                // isRemoving: a just-dismissed dialog stays findable by tag until its async
                // removal commits — updating it would show nothing, so treat it as absent
                ProgressDialogFragment existing =
                        (ProgressDialogFragment) fm.findFragmentByTag(ProgressDialogFragment.TAG);
                if (existing != null && !existing.isRemoving()) {
                    existing.update(title, msg);
                    return;
                }
                // showNow commits synchronously so a second showProgress/updateProgress
                // call can find the dialog by tag immediately (no stacked dialogs)
                ProgressDialogFragment.Companion.newInstance(title, msg)
                        .showNow(fm, ProgressDialogFragment.TAG);
            }
        });
    }

    public void updateProgress(final String title, final String msg)
    {
        progressHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                if (isDestroyed()) return;
                ProgressDialogFragment f = (ProgressDialogFragment)
                        getSupportFragmentManager().findFragmentByTag(ProgressDialogFragment.TAG);
                if (f != null) f.update(title, msg);
            }
        });
    }

    public void hideProgress()
    {
        progressHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                if (isDestroyed()) return;
                ProgressDialogFragment f = (ProgressDialogFragment)
                        getSupportFragmentManager().findFragmentByTag(ProgressDialogFragment.TAG);
                if (f != null) f.dismissAllowingStateLoss();
            }
        });
    }

    // Legacy progress API: the AlertDialog implementation was replaced by
    // ProgressDialogFragment (survives config changes, never stacks, ordered
    // show/update/hide). Signatures kept so existing call sites need no change.
    //
    // mContext routing: some flows construct an ActivityBase subclass manually and
    // use it as a helper object. Such an instance has no attached FragmentManager,
    // so the dialog must be hosted by the real activity passed as mContext — exactly
    // like the old implementation, which built its AlertDialog on mContext rather
    // than `this`. close/update on the helper instance follow the same remembered host.
    private WeakReference<ActivityBase> progressHostRef;

    private ActivityBase progressHost()
    {
        ActivityBase host = progressHostRef != null ? progressHostRef.get() : null;
        return host != null ? host : this;
    }

    public void startProgressDialog(final Context mContext, final String title, final String msg)
    {
        ActivityBase host = (mContext instanceof ActivityBase && mContext != this)
                ? (ActivityBase) mContext : null;
        progressHostRef = host != null ? new WeakReference<>(host) : null;
        (host != null ? host : this).showProgress(title, msg);
    }

    public void closeProgressDialog()
    {
        progressHost().hideProgress();
    }

    protected Runnable changeMessage = new Runnable()
    {
        @Override
        public void run()
        {
            if (pDMsg != null)
            {
                progressHost().updateProgress(null, pDMsg);
            }
        }
    };

    protected Runnable changeTitle = new Runnable()
    {
        @Override
        public void run()
        {
            if (pDTitle != null)
            {
                progressHost().updateProgress(pDTitle, null);
            }
        }
    };


    @Override
    protected void onDestroy()
    {
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        // TODO Auto-generated method stub
        super.onResume();
    }

    @Override
    protected void onPause()
    {
        super.onPause();
    }


    protected void print(List<MulPrintStrEntity> list)
    {
        try {
            //int fontSize = FontFamily.MIDDLE;
            Bundle config = new Bundle();
            //config.putString(PrinterConfig.COMMON_TYPEFACE_PATH, fontPath);
            config.putInt(PrinterConfig.COMMON_GRAYLEVEL, 30);
            DeviceHelper.getPrinter().printStr(list, new OnPrintListener.Stub()
            {
                @Override
                public void onPrintResult(int result) throws RemoteException
                {
                    /*this.runOnUiThread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            //button.setEnabled(true);
                        }
                    });*/
                    //showResult(textView, result == ServiceResult.Success ? getString(R.string.msg_succ) : getString(R.string.msg_fail));
                    //this.sysPrint(result == ServiceResult.Success ? getString(R.string.msg_succ) : getString(R.string.msg_fail));
                }
            }, config);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    protected void ToastMake(Context mContext,String msg,int duration)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Toast.makeText(mContext,msg,duration).show();
            }
        });
    }

    public void PINDialog(String type) {
        DialogInterface.OnKeyListener keyListener = new DialogInterface.OnKeyListener()
        {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent KEvent)
            {
                return keyCode == KeyEvent.KEYCODE_HOME;
            }
        };
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setOnKeyListener(keyListener);
        LayoutInflater inflater = this.getLayoutInflater();
        @SuppressLint("InflateParams") View dialogView = inflater.inflate(R.layout.actvivty_passwordlock, null);
        EditText edTx = dialogView.findViewById(R.id.passwordString);
        edTx.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent)
            {
                if (actionId == EditorInfo.IME_ACTION_SEND)
                {
                    if (edTx.getText().length() != 6 || edTx.getText().length() == 0 ) {
                        edTx.setText("");
                        Toast.makeText(
                            getApplicationContext(),
                            "Invalid PIN",
                            Toast.LENGTH_SHORT
                        ).show();
                    } else {
                        terminalPIN = edTx.getText().toString();
                        new VoidSaleTask().execute(terminalPIN, type);
                        edTx.setText("");
                    }
                    return true;
                }
                return false;
            }
        });
        TextView position = dialogView.findViewById(R.id.positionBtn);
        position.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view)
            {
                if (edTx.getText().length() != 6 || edTx.getText().length() == 0 ) {
                    edTx.setText("");
                    Toast.makeText(
                        getApplicationContext(),
                        "Invalid PIN",
                        Toast.LENGTH_SHORT
                    ).show();
                } else {
                    terminalPIN = edTx.getText().toString();
                    new VoidSaleTask().execute(terminalPIN, type);
                    edTx.setText("");
                }
            }
        });
        TextView negative = dialogView.findViewById(R.id.negativeBtn);
        negative.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                edTx.setText("");
                onBackPressed();
            }
        });
        alertDialogBuilder.setView(dialogView);
        alertDialogBuilder.setCancelable(false);
        alertDialog_1 = alertDialogBuilder.create();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(Objects.requireNonNull(alertDialog_1.getWindow()).getAttributes());
        lp.width = 600;
        alertDialog_1.getWindow().setAttributes(lp);
        alertDialog_1.show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Intent intent = new Intent(this, AttendActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    @SuppressLint("StaticFieldLeak")
    private class VoidSaleTask extends AsyncTask<String, String, Boolean> {
        @Override
        protected void onPreExecute() {
            startProgressDialog(ActivityBase.this, "Verifying PIN", "Loading...");
            super.onPreExecute();
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        protected Boolean doInBackground(String... strings) {
            try {
                HelperLog log = new helpers.HelperLog(
                    HelperCommon.getSession(),
                    TmsHelper.checkIsConnectedWifi(getApplicationContext()),
                    Utils.getIPAddress(),
                    "Verifying Pin",
                    ActivityBase.this.getClass().getSimpleName(),
                    ActivityBase.this.getClass().getName()
                );
                Boolean result = TmsHelper.checkTerminalPIN(log, ActivityBase.this, strings[0], strings[1]);
                log.logToFile(EnumLogFileName.TerminaLog);
                return result;
            } catch (Exception e) {
                e.printStackTrace();
            }

            return false;
        }

        @Override
        protected void onPostExecute(Boolean aBoolean) {
            super.onPostExecute(aBoolean);
            closeProgressDialog();
            if (aBoolean == true) {
                try {
                    alertDialog_1.dismiss();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            } else {
                Toast.makeText(ActivityBase.this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
            }
        }
    }


}
