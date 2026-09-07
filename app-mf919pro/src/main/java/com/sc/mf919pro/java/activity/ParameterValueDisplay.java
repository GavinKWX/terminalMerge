package com.sc.mf919pro.java.activity;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.sc.mf919pro.R;

public class ParameterValueDisplay
{
    private final View mView;

    public ParameterValueDisplay(Activity mContext, int id, String tag, String value)
    {
        LayoutInflater layoutInflater = mContext.getLayoutInflater();
        mView=layoutInflater.inflate(R.layout.fragment_parameter_tag_value, null, true);
        mView.setId(id);
        ((TextView) mView.findViewById(R.id.tag)).setText(tag);
        ((TextView) mView.findViewById(R.id.value)).setText(value);
    }

    public View getView()
    {
        return mView;
    }
}
