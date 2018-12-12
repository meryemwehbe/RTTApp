package com.fit_iot.wild_minimal;

import android.content.Context;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;

/**
 * Created by For on 4/24/2017.
 */



public class Mysingleton {
    private static Mysingleton mInstance;
    private RequestQueue requestQueue;
    private static Context mCtx;


    private Mysingleton (Context Context)
    {
        mCtx = Context;
        requestQueue = getRequestQueue();
    }

    public static  synchronized Mysingleton getInstance (Context context)
    {
        if (mInstance==null)
        {
            mInstance =new Mysingleton(context);
        }
        return mInstance;
    }

    public RequestQueue getRequestQueue() {

        if (requestQueue==null)
        {
            requestQueue = Volley.newRequestQueue(mCtx.getApplicationContext());
        }
        return requestQueue;

    }

    public<T> void addTorequestque(Request<T> request)
    {
        requestQueue.add(request);
    }
}
