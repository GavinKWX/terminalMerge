package com.sc.mf919.java.activity;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;import com.sc.mf919.R;

public class PassWordActivity extends AppCompatActivity
{
    int stages;
    EditText userInput;
    TextView msg;
    private static final String TAG = "PassWordActivity";
    private boolean closeInactivated;
    private boolean closeStatus;
    int timeOut;


    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_adminsignin);
        /*Intent intent=getIntent();
        stages = intent.getIntExtra("id",0);
        msg = findViewById(R.id.textView1);
        addMsgType(stages);
        userInput =findViewById(R.id.editTextDialogUserInput);
        if(stages==1 || stages==2){userInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);}
        userInput.addTextChangedListener(tx);
        showKeyboard();
        userInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event)
            {
                Utils.debugLogPrint(TAG, "onEditorAction: " + actionId);
                boolean handled = false;
                if (actionId == EditorInfo.IME_ACTION_DONE)
                {
                    closeKeyboard();
                    nextProcess();
                    handled = true;
                }
                return handled;
            }
        });
        if(stages==5 || stages==6){new autoClose().execute();}*/
    }

    private void addMsgType(int stages)
    {
        switch (stages)
        {
            case 0:
            case 4:
            case 5:
            case 6:
                msg.setText(R.string.ps1);
                break;
            case 1:
                msg.setText(R.string.ps2);
                break;
            case 2:
                msg.setText(R.string.ps3);
                break;
            case 3:
                msg.setText(R.string.ps4);
                break;
        }
    }

    /*private void nextProcess()
    {
        String user_text = userInput.getText().toString();
        boolean result=false;
        Class<?> cls= PassWordActivity.class;
        switch (stages)
        {
            case 0:
            case 4:
            case 5:
            case 6:
                result= Utils.verifySystemPW(user_text);
                break;
            case 1:
                cls= VoidSale.class;
                result= GeneralMethod.verifySettingPW(user_text);
                break;
            case 2:
                cls= AdmintActivity.class;
                if(user_text.equals("474448"))
                {
                    cls= VendorOption.class;
                    result=true;
                }
                else
                {
                    result= GeneralMethod.verifyAdminPW(user_text);
                }
                break;
            case 3:
                cls= VendorOption.class;
                result= GeneralMethod.verifyVendorPW(user_text);
                break;
        }

        if(result)
        {
            if(stages==0 || stages==4 || stages==5 || stages==6)
            {
                KioskConstant.setAttendStatus(2);
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("closeFlag",true);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                finish();
            }
            else
            {
                nextStep(cls);
            }
        }
        else
        {
            userInput.setText("");
            showKeyboard();
            Toast.makeText(this,"The password you have entered is incorrect." + " \n \n" + "Please try again!",Toast.LENGTH_SHORT).show();
        }
    }

    private void nextStep(Class<?> cls)
    {
        closeInactivated=false;
        closeStatus=false;
        Intent intent = new Intent(this,cls);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if ((keyCode == KeyEvent.KEYCODE_HOME))
        {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed()
    {

        if(closeInactivated)
        {
            closeInactivated=false;
        }
        else
        {
            switch (stages)
            {
                case 0:
                    nextStep(MainActivity.class);
                    break;
                case 1:
                case 4:
                    nextStep(AttendActivity.class);
                    break;
                case 2:
                    nextStep(Settings.class);
                    break;
                case 3:
                    nextStep(AdmintActivity.class);
                    break;
                case 5:
                    nextStep(UnattendedActivity.class);
                    break;
                case 6:
                    nextStep(UnattendedStandAloneActivity.class);
                    break;
            }
        }
    }

    public void showKeyboard()
    {
        userInput.requestFocus();
        InputMethodManager inputMethodManager = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null)
        {
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
            inputMethodManager.showSoftInput(userInput, InputMethodManager.SHOW_FORCED);
        }
    }

    public void closeKeyboard()
    {
        InputMethodManager inputMethodManager = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class autoClose extends AsyncTask<Void,String,Boolean>
    {

        @Override
        protected Boolean doInBackground(Void... voids)
        {
            timeOut= 1000;
            closeInactivated=true;
            while(closeInactivated)
            {
                timeOut--;
                GeneralMethod.DelayMili(10);
                if(timeOut<=0) {
                    closeStatus = true;
                    break;
                }
            }
            return closeStatus;
        }

        @Override
        protected void onPostExecute(Boolean aBoolean)
        {
            if(aBoolean)
            {
                closeKeyboard();
                switch (stages)
                {
                    case 0:
                        nextStep(MainActivity.class);
                        break;
                    case 1:
                        nextStep(AttendActivity.class);
                        break;
                    case 2:
                        nextStep(Settings.class);
                        break;
                    case 3:
                        nextStep(AdmintActivity.class);
                        break;
                    case 4:
                        nextStep(AttendActivity.class);
                        break;
                    case 5:
                        nextStep(UnattendedActivity.class);
                        break;
                }
            }
        }
    }

    TextWatcher tx = new TextWatcher()
    {
        @Override
        public void beforeTextChanged(CharSequence s, int start,
        int count, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start,
        int before, int count) {timeOut = 1000;}

        @Override
        public void afterTextChanged(Editable editable)
        {}
    };
*/
}
