package nus.dtn.app.broadcast;

/**
 * Created by Jabir on 4/10/2015.
 */
public class PlayerSteps
{
 String playerName="";
 int noOfSteps=0;
 int idleOrNoMessageIntervals=0;
 double distanceCovered=0;
    PlayerSteps(String s,double distanceCovered)
    {
        playerName=s;
        this.distanceCovered=distanceCovered;
    }

}
