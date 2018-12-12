package com.fit_iot.wild_minimal;


import android.util.Log;
import android.util.Pair;

import com.fit_iot.wild_minimal.R;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class HTTPPostActivity {

    // Debug log tag.
    private static final String TAG_HTTP_URL_CONNECTION = "HTTP_URL_CONNECTION";

    // Child thread sent message type value to activity main thread Handler.
    private static final int REQUEST_CODE_SHOW_RESPONSE_TEXT = 1;

    // The key of message stored server returned data.
    private static final String KEY_RESPONSE_TEXT = "KEY_RESPONSE_TEXT";

    // Request method GET. The value must be uppercase.
    private static final String REQUEST_METHOD_POST = "POST";


    /* Start a thread to send http request to web server use HttpURLConnection object. */
    public void startSendHttpRequestThread(final String reqUrl, final List macs , final List distances, final double lat, final double lon)
    {
        Thread sendHttpRequestThread = new Thread()
        {
            @Override
            public void run() {
                // Maintain http url connection.
                HttpURLConnection httpConn = null;



                try {
                    // Create a URL object use page url.
                    URL url = new URL(reqUrl);

                    // Open http connection to web server.
                    httpConn = (HttpURLConnection)url.openConnection();

                    // Set http request method to post.

                    httpConn.setReadTimeout(10000);
                    httpConn.setConnectTimeout(15000);
                    httpConn.setRequestMethod(REQUEST_METHOD_POST);
                    httpConn.setDoInput(true);
                    httpConn.setDoOutput(true);

                    List<Pair<String,String>> params = new ArrayList<Pair<String,String>>();


                    for(int i =0; i < macs.size() ;i ++){
                        params.add(new  Pair<String,String>("macs",macs.get(i).toString()));
                    }

                    for(int i =0; i < distances.size() ;i ++){
                        params.add(new  Pair<String,String>("ranges",distances.get(i).toString()));
                    }
                    params.add(new  Pair<String,String>("lat",Double.toString(lat)));
                    params.add(new  Pair<String,String>("lon",Double.toString(lon)));
                    OutputStream os = httpConn.getOutputStream();
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(os, "UTF-8"));
                    writer.write(getQuery(params));
                    writer.flush();
                    writer.close();
                    os.close();

                    httpConn.connect();
                    String response = httpConn.getResponseMessage();




                }catch(MalformedURLException ex)
                {
                    Log.e(TAG_HTTP_URL_CONNECTION, ex.getMessage(), ex);
                }catch(IOException ex)
                {
                    Log.e(TAG_HTTP_URL_CONNECTION, ex.getMessage(), ex);
                }finally {
                        if (httpConn != null) {
                            httpConn.disconnect();
                            httpConn = null;
                        }

                }
            }
        };
        // Start the child thread to request web page.
        sendHttpRequestThread.start();
    }


    private String getQuery(List<Pair<String,String>> params) throws UnsupportedEncodingException
    {
        StringBuilder result = new StringBuilder();
        boolean first = true;

        for (Pair<String,String> pair : params)
        {
            if (first)
                first = false;
            else
                result.append("&");

            result.append(URLEncoder.encode(pair.first, "UTF-8"));
            result.append("=");
            result.append(URLEncoder.encode(pair.second, "UTF-8"));
        }

        return result.toString();
    }
}
