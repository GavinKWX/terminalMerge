package com.sc.mf919pro.java.activity;

import android.app.Service;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class Connection
{
    Context context;
    public Connection(Context ncontext)
    {
        this.context=ncontext;
    }

    public boolean isConnected()
    {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        if(connectivity !=null)
        {
            NetworkInfo info=connectivity.getActiveNetworkInfo();
            if(info!=null)
            {
                return info.getState() == NetworkInfo.State.CONNECTED;
            }
        }
        return false;
    }
}
