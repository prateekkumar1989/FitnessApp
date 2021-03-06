package nus.dtn.app.broadcast;
/*
This uses an implementation of a linear Kalman filter which is an estimator that converges to the true value over time.
We've used for a standard threshold algorithm to do step detection and is similar to the one at
http://stackoverflow.com/questions/4993993/how-to-detect-walking-with-android-accelerometer. In addition, we have also
implemented a smoothing average low-pass filter which gives comparable performance and can be optionally used as well.

Our own implementations which are either commented out here or are in a separate project folder include:
* Double Integration of the acceleration components relative to inertial system to calculate distance - gives too much error to be usable.
* Self implementation of the Kalman Filter which does not give results as consistent as the one used below.

*/
import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;

import nus.dtn.util.DtnMessage;
import nus.dtn.util.Descriptor;
import nus.dtn.api.fwdlayer.ForwardingLayerProxy;
import nus.dtn.api.fwdlayer.ForwardingLayerInterface;
import nus.dtn.api.fwdlayer.MessageListener;
import nus.dtn.middleware.api.DtnMiddlewareInterface;
import nus.dtn.middleware.api.DtnMiddlewareProxy;
import nus.dtn.middleware.api.MiddlewareEvent;
import nus.dtn.middleware.api.MiddlewareListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.util.*;

/** App that broadcasts messages to everyone using a Mobile DTN. */
public class BroadcastAppActivity extends Activity implements SensorEventListener, AdapterView.OnItemSelectedListener {

    /** Called when the activity is first created. */
    @Override
    public void onCreate ( Bundle savedInstanceState ) {
        super.onCreate ( savedInstanceState );

        try {

            // Specify what GUI to use
            setContentView ( R.layout.main );
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            lastUpdate = System.currentTimeMillis();
            TripleFilters[0] = new KalmanFilter(1, 1, 0.01f, 0.0025f);
            TripleFilters[1] = new KalmanFilter(1, 1, 0.01f, 0.0025f);
            TripleFilters[2] = new KalmanFilter(1, 1, 0.01f, 0.0025f);
            spinner = (Spinner)findViewById(R.id.spinner);
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(BroadcastAppActivity.this,
                    android.R.layout.simple_spinner_item,paths);

            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            spinner.setOnItemSelectedListener(this);

            // Set a handler to the current UI thread
            handler = new Handler();



            // Get references to the GUI widgets
            textView_Message = (TextView) findViewById ( R.id.TextView_Message );
            editText_Message = (EditText) findViewById ( R.id.EditText_Message );
            ownMacAddress=((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE)).getDeviceId();
            button_Calibarate_Start = (Button) findViewById ( R.id.Button_Calibarate_Start );
            button_Calibarate_Start.setOnClickListener ( new View.OnClickListener()
            {
                public void onClick ( View v )
                {
                    CalibarateAccelerometer(0);

                }

            });

            button_Calibarate_End = (Button) findViewById ( R.id.Button_Calibarate_End );
            button_Calibarate_End.setOnClickListener ( new View.OnClickListener()
            {
                public void onClick ( View v )
                {
                    CalculateNoOfSteps();
                    steps = 0; LastZ = z = 0;
                }

            });

            Button button_Challenge = (Button) findViewById ( R.id.Button_Challenge );
            button_Challenge.setOnClickListener ( new View.OnClickListener()
            {

                public void onClick ( View v ) {
                    if (!challenged) {
                        challenged = true;

                        Thread clickThread = new Thread() {
                            public void run() {

                                try {

                                    // Construct the DTN message
                                    isLeader = true;
                                    DtnMessage message = new DtnMessage();
                                    String chatMessage = editText_Message.getText().toString();
                                    ChallengeMessage obj = new ChallengeMessage();

                                    TelephonyManager telephonyManager =
                                            (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                                    //obj.MacAddress=telephonyManager.getDeviceId();
                                    String identity = telephonyManager.getDeviceId();
                                    String type = "challengeMessage";


                                    message.addData()                  // Create data chunk
                                            .writeString(type)  // Chat message
                                            .writeString(identity);
                                    // Broadcast the message using the fwd layer interface
                                    fwdLayer.sendMessage(descriptor, message, "everyone", null);
                                    startTime = System.currentTimeMillis();
                                    requestSent = true;
                                    // Tell the user that the message has been sent
                                    createToast("Chat message broadcast!");
                                    //          sleep(30000);
                                } catch (Exception e) {
                                    // Log the exception
                                    Log.e("BroadcastApp", "Exception while sending message", e);
                                    // Inform the user
                                    //createToast ( "Exception while sending message, check log" );
                                }
                            }
                        };
                        clickThread.start();

                        Thread waiting = new Thread() {
                            public void run() {
                                while (true) {
                                    if (System.currentTimeMillis() - startTime > 10000 && requestSent == true) {
                                        int i = 0;
                                        int size = IntroductoryMessageList.size();
                                        final int size1 = size;
                                        String s = "looping introdusctory msg";
                                        Log.e(String.valueOf(size1), "edc");
                               /* handler.post(new Runnable() {
                                    public void run() {
                                        textView_Message.setText("hello123");
                                    }
                                });*/
                                        String combinedAddresses = "";
                                        String type = "IntroductoryMessage";
                                        for (int j = 0; j < size; j++)
                                        {
                                            IntroductoryMessage messageM = IntroductoryMessageList.get(j);
                                            PlayerSteps p = new PlayerSteps(messageM.MacMessage, 0);
                                            p.idleOrNoMessageIntervals = 0;
                                            AllPlayersStepsList.add(p);
                                            String macMessage = messageM.MacMessage;
                                            combinedAddresses = combinedAddresses + macMessage;
                                            combinedAddresses = combinedAddresses + "/";

                                        }
                                        //PlayerSteps self=new PlayerSteps(ownMacAddress,0);
                                        //AllPlayersStepsList.add(self);
                                        combinedAddresses = combinedAddresses + ownMacAddress + "/";
                                        for (i = 0; i < size; i++) {
                                            //log here

                                            IntroductoryMessage m = IntroductoryMessageList.get(i);
                                            String macAddress = m.MacMessage;

                                            DtnMessage indroductoryMessage = new DtnMessage();
                                            try {




                                                indroductoryMessage.addData().writeString(type)
                                                        //.addData().writeInt(size)
                                                        .writeString(combinedAddresses);

                                                fwdLayer.sendMessage(descriptor, indroductoryMessage, macAddress, null);
                                            } catch (Exception e) {

                                            }
                                        }
                                        break;
                                    }
                                    //start the game
                                }
                                gameStartTime = System.currentTimeMillis();
                                intervalStart = gameStartTime;
                                CalibarateAccelerometer(0);
                                gameStarted=true;

                                    while (System.currentTimeMillis() - gameStartTime < 600000) {

                                        if (((System.currentTimeMillis() - intervalStart > 10000 && noOfMessagesReceived != 0) || (noOfMessagesReceived == AllPlayersStepsList.size() && noOfMessagesReceived != 0))) {
                                            nowUpdatingLeaderboard = true;
                                            getNoOfStepsTaken_duringGame = steps/2;
                                            if (!updatingPlayerList) {
                                                final String s = "All players and" + AllPlayersStepsList.size() + " " + PlayerStepsList.size() + " " + noOfMessagesReceived;

                                                double d = System.currentTimeMillis();
                                                double qsx = intervalStart;
                                                double kr = System.currentTimeMillis() - intervalStart;
                                                Log.e("The parameters", "All players and" + AllPlayersStepsList.size() + " " + PlayerStepsList.size() + " " + noOfMessagesReceived + " " + d + " " + qsx + " " + kr);
                                                Log.e("x", "y");
                                                List<PlayerSteps> NoUpdats = new ArrayList();
                                                for (int i = 0; i < AllPlayersStepsList.size(); i++) {
                                                    Log.e("The param1", "The param2");

                                                    String macAddress = AllPlayersStepsList.get(i).playerName;
                                                    Log.e("got mac address","got mac address");
                                                   // int stepsCount = AllPlayersStepsList.get(i).noOfSteps;
                                                    int flag = 0;
                                                    for (int j = 0; j < PlayerStepsList.size(); j++) {
                                                        Log.e("got mac address1","got mac address1");
                                                        if (macAddress.equals(PlayerStepsList.get(j).playerName)) {
                                                            Log.e("equals", "equals");
                                                            Log.e("steps are", new Integer(PlayerStepsList.get(j).noOfSteps).toString());
                                                            int noOfstepsinArraylist = PlayerStepsList.get(j).noOfSteps;
                                                            AllPlayersStepsList.get(i).noOfSteps = noOfstepsinArraylist;
                                                            AllPlayersStepsList.get(i).noOfSteps = 0;
                                                            Log.e("The number of steps in arraylist", new Integer(AllPlayersStepsList.get(i).noOfSteps).toString());
                                                            flag = 1;
                                                            break;
                                                        }

                                                    }
                                                    if (flag != 1) {
                                                        PlayerSteps ps = AllPlayersStepsList.get(i);
                                                        Log.e("if not exception","if not exception");
                                                        ps.idleOrNoMessageIntervals++;

                                                        if (AllPlayersStepsList.get(i).idleOrNoMessageIntervals < 2) {
                                                            NoUpdats.add(ps);

                                                        }
                                                    }
                                                }
                                                //PlayerStepsList.clear();
                                                AllPlayersStepsList.clear();
                                                noOfMessagesReceived = 0;
                                                for (int i = 0; i < PlayerStepsList.size(); i++) {
                                                    AllPlayersStepsList.add(PlayerStepsList.get(i));
                                                }

                                                for (int i = 0; i < NoUpdats.size(); i++) {
                                                    AllPlayersStepsList.add(NoUpdats.get(i));
                                                }
                                                PlayerStepsList.clear();
                                                //find the leader
                                                PlayerSteps leaderSteps=new PlayerSteps(ownMacAddress,getNoOfStepsTaken_duringGame*distancePerStep);
                                                Log.e("The number of steps in arraylist1", new Integer(AllPlayersStepsList.get(0).noOfSteps).toString());
                                            /*if (!isDisqualified) {
                                                for (int i = 0; i < AllPlayersStepsList.size(); i++) {
                                                    if (AllPlayersStepsList.get(i).playerName.equals(ownMacAddress)) {
                                                        //find idle or not
                                                        Log.e("own mac  addr","ownmac addr");
                                                        AllPlayersStepsList.get(i).noOfSteps = getNoOfStepsTaken_duringGame;
                                                        AllPlayersStepsList.get(i).idleOrNoMessageIntervals = 0;
                                                    }
                                                }
                                            }*/
                                                 AllPlayersStepsList.add(leaderSteps);
                                                List<PlayerSteps> temp = new ArrayList();
                                                Log.e("The number of steps in arraylist", new Double(AllPlayersStepsList.get(0).distanceCovered).toString());
                                                int size = AllPlayersStepsList.size();
                                                for (int i = 0; i < size; i++) {
                                                    Log.e("in creating temp", "edc");
                                                    PlayerSteps p = AllPlayersStepsList.get(0);
                                                    //write here
                                                    AllPlayersStepsList.remove(0);
                                                    int flag = 0;
                                                    for (int k = 0; k < temp.size(); k++) {
                                                        PlayerSteps p1 = temp.get(k);
                                                        if (p.distanceCovered > p1.distanceCovered) {
                                                            temp.add(k, p);
                                                            flag = 1;
                                                            break;
                                                        }
                                                    }
                                                    if (flag != 1) {
                                                        temp.add(p);
                                                        Log.e("The number of steps in p", new Double(p.distanceCovered).toString());
                                                    }
                                                }
                                                String combinedString = "";
                                                String leaderboard="The leader board is - ";
                                                for (int k = 0; k < temp.size(); k++) {
                                                    Log.e("In temp", "qaz");
                                                    if(!temp.get(k).playerName.equals(ownMacAddress))
                                                       AllPlayersStepsList.add(temp.get(k));
                                                    Log.e("The number of steps in temp", new Double(temp.get(k).distanceCovered).toString());
                                                    combinedString = combinedString + temp.get(k).playerName + "/" + temp.get(k).distanceCovered + "/";
                                                    leaderboard=leaderboard+"(IMEI: "+temp.get(k).playerName+" "+" - Distance Covered:"+temp.get(k).distanceCovered+")"+'\n';

                                               }
                                                final String leaderboardString=leaderboard;
                                                handler.post(new Runnable() {
                                                    public void run() {
                                                        textView_Message.setText(leaderboardString);
                                                    }
                                                });
                                                Log.e("dcf", combinedString);
                                                //send to everyone
                                                try
                                                {
                                                    DtnMessage stepsOfAllPlayers = new DtnMessage();
                                                    stepsOfAllPlayers.addData().writeString("StepsOfAllPlayers")
                                                            .writeString(combinedString);
                                                    for (int k = 0; k < temp.size(); k++) {
                                                        Log.e("sending from temp", "other param");
                                                        if (!temp.get(k).playerName.equals(ownMacAddress))
                                                            fwdLayer.sendMessage(descriptor, stepsOfAllPlayers, temp.get(k).playerName, null);
                                                    }
                                                } catch (Exception e) {
                                                    Log.e("the exception is looping", e.toString());
                                                }
                                                intervalStart = System.currentTimeMillis();

                                                temp.clear();
                                                PlayerStepsList.clear();
                                                nowUpdatingLeaderboard = false;
                                            }


                                        }
                                    }


                            }
                        };
                        waiting.start();
                    }
                }

            });


            // Start the middleware
            middleware = new DtnMiddlewareProxy ( getApplicationContext() );
            middleware.start ( new MiddlewareListener() {
                public void onMiddlewareEvent ( MiddlewareEvent event ) {
                    try {

                        // Check if the middleware failed to start
                        if ( event.getEventType() != MiddlewareEvent.MIDDLEWARE_STARTED ) {
                            throw new Exception( "Middleware failed to start, is it installed?" );
                        }

                        // Get the fwd layer API
                        fwdLayer = new ForwardingLayerProxy ( middleware );

                        // Get a descriptor for this user
                        // Typically, the user enters the username, but here we simply use IMEI number
                        TelephonyManager telephonyManager =
                                (TelephonyManager) getSystemService ( Context.TELEPHONY_SERVICE );
                        descriptor = fwdLayer.getDescriptor ( "nus.dtn.app.broadcast" , telephonyManager.getDeviceId() );

                        // Set the broadcast address
                        fwdLayer.setBroadcastAddress ( "nus.dtn.app.broadcast" , "everyone" );

                        // Register a listener for received chat messages
                        ChatMessageListener messageListener = new ChatMessageListener();
                        fwdLayer.addMessageListener ( descriptor , messageListener );
                    }
                    catch ( Exception e ) {
                        // Log the exception
                        Log.e ( "BroadcastApp" , "Exception in middleware start listener" , e );
                        // Inform the user
                        createToast ( "Exception in middleware start listener, check log" );
                    }
                }
            } );
        }
        catch ( Exception e ) {
            // Log the exception
            Log.e ( "BroadcastApp" , "Exception in onCreate()" , e );
            // Inform the user
            createToast ( "Exception in onCreate(), check log" );
        }
    }

    void OnGameStarted()
    {
        CalibarateAccelerometer(0);
        gameStarted=true;
        lastSent=System.currentTimeMillis();
        while(true)
        {
            //game started code
            /*handler.post(new Runnable() {
                public void run() {
                    textView_Message.setText("gameStarted");
                }
            });*/
            Log.e("game started","started");
            if(System.currentTimeMillis()-lastSent>2000)
            {
                getNoOfStepsTaken_duringGame=steps/2;
                /*handler.post(new Runnable() {
                    public void run() {
                        textView_Message.setText("Sending");
                    }
                });*/
                try
                {
                    DtnMessage StepsTakenMessage = new DtnMessage();
                    String addressNSteps=ownMacAddress;
                    addressNSteps=addressNSteps+"/"+getNoOfStepsTaken_duringGame*distancePerStep;
                    StepsTakenMessage.addData().writeString("StepsMessage")
                            //StepsTakenMessage.addData().writeInt(getNoOfStepsTaken_duringGame);
                            .writeString(addressNSteps);
                    fwdLayer.sendMessage(descriptor, StepsTakenMessage, leaderAddress, null);
                }

                catch(Exception e)
                {
                    Log.e("the exception of send",e.toString());
                }
                lastSent=System.currentTimeMillis();
            }

        }

    }

    void  CalculateNoOfSteps()
    {
        sensorManager.unregisterListener(this);
        noOfStepsTaken = steps/2;
        distancePerStep = Double.parseDouble(editText_Message.getText().toString()) / noOfStepsTaken ;
        editText_Message.setText("Distance per step in m: " + distancePerStep);
    }
    int CalibarateAccelerometer(int action)
    {
        try {
            Thread.sleep(3000);
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        steps = 0; LastZ = z = 0;
        sensorManager.registerListener(this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                LEG_SENSOR_RATE);
        return 1;
    }

    /** Called when the activity is destroyed. */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            // Stop the middleware
            // Note: This automatically stops the API proxies, and releases descriptors/listeners
            middleware.stop();
        }
        catch ( Exception e ) {
            // Log the exception
            Log.e ( "BroadcastApp" , "Exception on stopping middleware" , e );
            // Inform the user
            createToast ( "Exception while stopping middleware, check log" );
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        switch (i) {
            case 0:
                filter = "kalmanfilter";
                break;
            case 1:
                filter = "lowpassfilter";
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER){
            return;
        }
        //getAccelerometer(event);

        float[] values = event.values;
        if(filter == "lowpassfilter")
            z = lowPass(values[2],z);
        else
        if(filter == "kalmanfilter")
            z = filter(values[2]);
        System.out.println(filter + " z = " + z);

        if (Math.abs(z - LastZ) > STEP_ACCELERATION_THRESHOLD)
        {
            InactivityCount = 0;
            int currentActivity = (z > LastZ) ? FORWARD_LEG : BACKWARD_LEG;
            if (currentActivity != LastActivity)
            {
                LastActivity = currentActivity;
                steps++;
                if(!gameStarted) textView_Message.setText(Integer.toString(steps/2));
            }
        }
        else
        {
            if (InactivityCount > INACTIVITY_THRESHOLD)
            {
                if (LastActivity != NO_MOVEMENT)
                {
                    LastActivity = NO_MOVEMENT;
                    if(!gameStarted) textView_Message.setText("Idling");
                }
            }
            else InactivityCount++;
        }
        LastZ = z;

        /*
        // Movement
        float xa = values[0];
        float ya = values[1];
        float za = Math.abs(values[2])-Math.abs(SensorManager.GRAVITY_EARTH);

        float a = (float) ( Math.sqrt( ( xa * xa + ya * ya + za * za) ) );
        long actualTime = System.currentTimeMillis();
        if (actualTime - lastUpdate < 200)
        {
            return;
        }
        float dt = (actualTime - lastUpdate)/1000;
        lastUpdate = actualTime;

        d += (float) (u*dt + 0.5*a*dt*dt);
        currentA.setText(Float.toString(d));
        u = u*dt;
        */
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    float filter(float measurement){
        float f1 = TripleFilters[0].correct(measurement);
        float f2 = TripleFilters[1].correct(f1);
        float f3 = TripleFilters[2].correct(f2);

        return f3;
    }

    private void getAccelerometer(SensorEvent event) {

        float[] values = event.values;


        // Movement
        float x = values[0];
        float y = values[1];
        float z = values[2];

        float g =  ( x * x + y * y + z * z) / (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH) ;
        long actualTime = System.currentTimeMillis();
        if (actualTime - lastUpdate < 200)
        {
            return;
        }
        lastUpdate = actualTime;

        LowPassX = lowPass(x,LowPassX);
        LowPassY = lowPass(y,LowPassY);
        LowPassZ = lowPass(z,LowPassZ);
        lowPassG = lowPass(g,lowPassG);

        //logReading(LowPassX, LowPassY, LowPassZ, lowPassG);

        currentX.setText(Float.toString(x));
        currentY.setText(Float.toString(y));
        currentZ.setText(Float.toString(z));
        currentA.setText(Double.toString(Math.round(g)));
    }

    float lowPass(float current, float last)
    {
        /* This is a standard implementation from http://www.wrox.com/WileyCDA/WroxTitle/Professional-Android-Sensor-Programming.productCd-1118183487.html */
        return last*(1.0f - a) + current*a;
    }

    /** Listener for received chat messages. */
    private class ChatMessageListener
            implements MessageListener {

        /** {@inheritDoc} */
        public void onMessageReceived ( String source ,
                                        String destination ,
                                        DtnMessage message ) {

            try {

                // Read the DTN message
                // Data part
                //TextView textView_Message=(TextView)findViewById ( R.id.TextView_Message );
                handler.post(new Runnable() {
                    public void run() {
                        textView_Message.setText("msgReceived");
                    }
                });
                message.switchToData();
                String type = message.readString();
                String messageRecvd=message.readString();
                final  String messageRecvdDispaly=messageRecvd;
                final String messageRecvdType=type;
                //ChallengeReplyMessage obj=null;
                // ChallengeMessage challengeMessage=null;
                Log.e("BroadcastApp", "Exception on message event"+type+" "+ messageRecvd);
                try
                {
                    handler.post(new Runnable() {
                        public void run() {
                            textView_Message.setText(messageRecvdDispaly+" "+messageRecvdType);
                        }
                    });
                }
                catch(Exception ex1)
                {

                }
                final String newText;

                ObjectInputStream si;
                try
                {

                    if(isLeader) {

                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText("Leader");
                            }
                        });
                        if (type.equals("challengeReplyMessage")) {
                            handler.post(new Runnable() {
                                public void run() {
                                    textView_Message.setText("gotreply");
                                }
                            });
                            if (System.currentTimeMillis() - startTime <= timeout) {
                                IntroductoryMessage m = new IntroductoryMessage();
                                m.MacMessage = messageRecvd;
                                IntroductoryMessageList.add(m);
                                isTimeout = false;
                                final String textFieldString = messageRecvd;
                                handler.post(new Runnable() {
                                    public void run() {
                                        textView_Message.setText(textFieldString);
                                    }

                                });

                            } else {
                                isTimeout = true;


                            }
                        }
                        else if (type.equals("StepsMessage"))
                        {
                            Log.e("got steps msg", "got msg");
                            int index = messageRecvd.indexOf("/");
                            String macAddress = messageRecvd.substring(0, index);
                            Log.e("12","34");
                            String noOfSteps = messageRecvd.substring(index + 1, messageRecvd.length());
                            String test=noOfSteps;
                            Log.e("56",test);
                            PlayerSteps p = new PlayerSteps(macAddress, Double.parseDouble(noOfSteps));
                            final String stqa=noOfSteps;
                            handler.post(new Runnable() {
                                public void run() {
                                    textView_Message.setText(textView_Message.getText()+ " "+stqa);
                                }
                            });
                            int flag = 0;

                            synchronized (this)
                            {
                                while(true)
                                {
                                    if (!nowUpdatingLeaderboard)
                                    {
                                        updatingPlayerList = true;
                                        for (int i = 0; i < PlayerStepsList.size(); i++) {
                                            if (PlayerStepsList.get(i).playerName.equals(macAddress))
                                            {
                                                PlayerStepsList.get(i).distanceCovered = Double.parseDouble(noOfSteps);
                                                //Integer t=new Integer(PlayerStepsList.get(i).noOfSteps);


                                                flag = 1;
                                            }
                                        }
                                        if (flag == 0) {
                                            PlayerStepsList.add(p);
                                            noOfMessagesReceived++;
                                        }
                                        Log.e("No of steps",new Double(p.noOfSteps).toString());
                                        updatingPlayerList = false;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    else if(type.equals("challengeMessage"))
                    {
                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText("rECEIVED1");
                            }
                        });
                        //challengeMessage=new ChallengeMessage();
                        //challengeMessage=(ChallengeMessage)si.readObject();
                        //Create a dialog box
                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText("RECEIVED2");
                            }
                        });

                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText("1234");
                            }
                        });
                        //String macAddress = chatMessage;

                        TelephonyManager telephonyManager =
                                (TelephonyManager) getSystemService ( Context.TELEPHONY_SERVICE );
                        String  challengeReplyMessage=telephonyManager.getDeviceId();
                        DtnMessage replyMessage = new DtnMessage();
                        //ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText("5678");
                            }
                        });
                        //ObjectOutputStream so = new ObjectOutputStream(bo);
                        //so.writeObject(challengeReplymessage);
                        //so.flush();
                        ;
                        final String textFieldString=messageRecvd;
                        leaderAddress=messageRecvd;
                        replyMessage.addData().writeString("challengeReplyMessage")
                                .writeString(challengeReplyMessage);

                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText(textFieldString);
                            }
                        });
                        fwdLayer.sendMessage ( descriptor , replyMessage , messageRecvd , null );
                    }

                    else if(type.equals("StepsOfAllPlayers"))
                    {
                        Log.e("Steps of all players 123","edcf");
                        int toGet=0;
                        int start=0;
                        String name="";
                        double distanceCovered=0;
                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText("stepsMessage");
                            }
                        });
                        for(int i=0;i<messageRecvd.length();i++)
                        {
                            if(messageRecvd.charAt(i)=='/')
                            {
                                String s=messageRecvd.substring(start,i);
                                if(toGet==0)
                                {
                                    name = s;
                                    toGet = 1;
                                    start=i+1;
                                }
                                else
                                {
                                    distanceCovered = Double.parseDouble(s);
                                    toGet = 0;
                                    start=i+1;
                                    PlayerSteps pSteps=new PlayerSteps(name,distanceCovered);
                                    //PlayerStepsList.add(pSteps);
                                    //PlayerStepsMap.put(name,steps);
                                    int k=0,flag=0;
                                    PlayerStepsList.add(pSteps);

                                }

                            }
                        }
                        String leaderboard="The leaderboard is - ";
                        int size=PlayerStepsList.size();
                        for(int x=0;x<size;x++)
                        {
                            //print the values
                            PlayerSteps p=PlayerStepsList.get(0);
                            PlayerStepsList.remove(0);
                            leaderboard=leaderboard+"(IMEI: "+p.playerName+" "+" - Distance Covered:"+p.distanceCovered+")"+'\n';

                        }

                        final String leaderBoardstringTextView=leaderboard;
                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText(leaderBoardstringTextView);
                            }
                        });

                    }
                    else if(type.equals("IntroductoryMessage"))
                    {
                        int start=0;
                        String s="";
                        for(int i=0;i<messageRecvd.length();i++)
                        {
                            if(messageRecvd.charAt(i)=='/')
                            {
                                s=s+messageRecvd.substring(start,i)+" ";
                                start=i+1;

                            }
                        }
                        final String introMessage=s;
                        handler.post(new Runnable() {
                            public void run() {
                                textView_Message.setText("Intro everyone"+ " "+":"+introMessage);
                            }
                        });
                        introdone=true;
                        Thread th=new Thread()
                        {
                            public void run()
                            {
                                while(!introdone)
                                {

                                }
                                OnGameStarted();
                            }
                        };
                        th.start();
                    }



                }
                catch (Exception e)
                {
                    final String f=e.getStackTrace().toString();
                    handler.post(new Runnable() {
                        public void run() {
                            Log.e("Tnvalid format exception", f);
                            textView_Message.setText(textView_Message.getText()+" "+f);
                        }
                    });
                }


            }
            catch ( Exception e )
            {
                // Log the exception
                Log.e("BroadcastApp", "Exception on message event", e);
                // Tell the user
                createToast ( "Exception on message event, check log" );
            }
        }
    }

    /** Helper method to create toasts. */
    private void createToast ( String toastMessage ) {

        // Use a 'final' local variable, otherwise the compiler will complain
        final String toastMessageFinal = toastMessage;

        // Post a runnable in the Main UI thread
        handler.post ( new Runnable() {
            @Override
            public void run() {
                Toast.makeText ( getApplicationContext() ,
                        toastMessageFinal ,
                        Toast.LENGTH_SHORT ).show();
            }
        } );
    }

    /** Text View (displays messages). */
    private TextView textView_Message;
    /** Edit Text (user enters message here). */
    private EditText editText_Message;
    /** Button to trigger action (sending message). */
    private Button button_Calibarate_Start;
    private Button button_Calibarate_End;
    private Button button_Challenge;

    /** DTN Middleware API. */
    private DtnMiddlewareInterface middleware;
    /** Fwd layer API. */
    private ForwardingLayerInterface fwdLayer;

    /** Sender's descriptor. */
    private Descriptor descriptor;

    /** Handler to the main thread to do UI stuff. */
    private Handler handler;
    private boolean isLeader=false;
    private int noOfStepsTaken=0;
    private double distancePerStep=0.5f;
    private double startTime=0;
    private double timeout=10000;
    //private boolean isLeader=false;
    private boolean isTimeout=false;
    private boolean challenged=false;
    private double lastSent=0;
    private int getNoOfStepsTaken_duringGame=0;
    private int numberOfPlayers=0;
    private String ownMacAddress="";
    private List<IntroductoryMessage>IntroductoryMessageList=new ArrayList();
    private List<PlayerSteps>PlayerStepsList=new ArrayList();
    private double gameStartTime=0;
    private HashMap PlayerStepsMap=new HashMap();
    private List<PlayerSteps>AllPlayersStepsList=new ArrayList();
    private int noOfMessagesReceived=0;
    boolean isDisqualified=false;
    boolean requestSent=false;
    boolean introdone=false;
    String leaderAddress="";
    double intervalStart=0;
    boolean nowUpdatingLeaderboard=false;
    boolean updatingPlayerList=false;
    boolean gameStarted=false;










    final float 	STEP_ACCELERATION_THRESHOLD	= 1; //0.05f for local KF
    final int 		INACTIVITY_THRESHOLD	= 10;
    final int		    NO_MOVEMENT 	= 0;
    final int 		FORWARD_LEG	= 1;
    final int 		BACKWARD_LEG 	= 2;
    final int 		LEG_SENSOR_RATE		= 60000; //SensorManager.SENSOR_DELAY_UI;


    float LastZ;
    int LastActivity = NO_MOVEMENT;
    int InactivityCount = 0;
    KalmanFilter TripleFilters[] = new KalmanFilter[3];
    int steps;
    String filter;

    private Spinner spinner;
    private static final String[]paths = {"Kalman Filter", "Low Pass Filter"};

    SensorManager sensorManager;
    boolean color = false;
    View view;
    long lastUpdate;
    TextView currentX, currentY, currentZ, currentA;
    float a = 0.4f;
    float z = SensorManager.GRAVITY_EARTH;
    float LowPassX, LowPassY, LowPassZ, lowPassG;
    File myFile;
    public PrintWriter LogFileOut;
    public PrintWriter locationLogFileOut;
    private float u=0, d=0;
    EditText cd;
    int calibrationdistance;
}
