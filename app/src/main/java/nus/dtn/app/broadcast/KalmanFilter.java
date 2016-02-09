package nus.dtn.app.broadcast;

/*
This is an implementation of a linear Kalman filter which is an estimator that converges to the true value over time.
We've used for a standard threshold algorithm to do step detection and is similar to the one at
http://stackoverflow.com/questions/4993993/how-to-detect-walking-with-android-accelerometer. In addition, we have also
implemented a smoothing average low-pass filter which gives comparable performance and can be optionally used as well.

Our own implementations which are either commented out here or are in a separate project folder include:
* Double Integration of the acceleration components relative to inertial system to calculate distance - gives too much error to be usable.
* Self implementation of the Kalman Filter which does not give results as consistent as the one used below.

*/
import android.hardware.SensorManager;

public class KalmanFilter {
    float predicted_state;
    float predicted_covariance;
    float factor_real_previous_value;
    float factor_measured_real_value;
    float measurement_noise;
    float environment_noise;
    float current_state = 0;
    float current_covariance = 0.1f;

	/*
    static int k;
    float xk=0f, Pk=1f, Kk=0f, R=1f;
	*/

    public KalmanFilter(float f, float h, float q, float r){
        factor_real_previous_value = f;
        factor_measured_real_value = h;
        measurement_noise = q;
        environment_noise = r;
    }

    public void init(float initialState, float initialCovariance){
        current_state = initialState;
        current_covariance = initialCovariance;
    }

    public float    correct(float measuredValue){

		/*
		//This commented implementation was made by us using theory from http://bilgin.esme.org/BitsBytes/KalmanFilterforDummies.aspx
        if(k == 0)
        {
            xk = SensorManager.GRAVITY_EARTH;
            Pk = 1;
        }
        else
        {
            Kk = Pk / (Pk + R);
            xk = xk + Kk*(measuredValue - xk);
            Pk = (1 - Kk)*Pk;
        }
        k++;
        System.out.println("xk=" + xk + " k=" + k + " Kk=" + Kk + " Pk=" + Pk);
        return xk;
		*/

        // time update - prediction
        predicted_state = factor_real_previous_value * current_state;
        predicted_covariance = factor_real_previous_value * current_covariance*factor_real_previous_value + measurement_noise;

        // measurement update - correction
        float k = factor_measured_real_value * predicted_covariance/(factor_measured_real_value * predicted_covariance * factor_measured_real_value + environment_noise);
        current_covariance = (1 - k * factor_measured_real_value) * predicted_covariance;
        return current_state = predicted_state + k * (measuredValue - factor_measured_real_value * predicted_state);
    }
}