/*
 * Copyright (c) 2023 Titan Robotics Club (http://www.titanrobotics.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package team492.drivebases;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Scanner;

import com.ctre.phoenix.ErrorCode;
import com.ctre.phoenix.motorcontrol.TalonFXControlMode;
import com.ctre.phoenix.sensors.CANCoder;
import com.ctre.phoenix.sensors.CANCoderStatusFrame;
import com.ctre.phoenix.sensors.SensorInitializationStrategy;
import com.ctre.phoenix.sensors.SensorTimeBase;

import TrcCommonLib.trclib.TrcWatchdogMgr;
import TrcCommonLib.trclib.TrcDbgTrace;
import TrcCommonLib.trclib.TrcEncoder;
import TrcCommonLib.trclib.TrcMotor;
import TrcCommonLib.trclib.TrcPidController;
import TrcCommonLib.trclib.TrcPidDrive;
import TrcCommonLib.trclib.TrcPurePursuitDrive;
import TrcCommonLib.trclib.TrcSwerveDriveBase;
import TrcCommonLib.trclib.TrcSwerveModule;
import TrcCommonLib.trclib.TrcTimer;
import TrcCommonLib.trclib.TrcRobot.RunMode;
import TrcCommonLib.trclib.TrcWatchdogMgr.Watchdog;
import TrcFrcLib.frclib.FrcAnalogEncoder;
import TrcFrcLib.frclib.FrcCANCoder;
import TrcFrcLib.frclib.FrcCANFalcon;
import TrcFrcLib.frclib.FrcCANSparkMax;
import TrcFrcLib.frclib.FrcCANTalon;
import TrcFrcLib.frclib.FrcFalconServo;
import TrcFrcLib.frclib.FrcPdp;
import team492.Robot;
import team492.RobotParams;

/**
 * This class creates the RobotDrive subsystem that consists of wheel motors and related objects for driving the
 * robot.
 */
public class SwerveDrive extends RobotDrive
{
    private static final String STEER_ZERO_CAL_FILE = "steerzeros.txt";
    private static final boolean logPoseEvents = false;
    private static final boolean tracePidInfo = false;
    //
    // Swerve steering motors and modules.
    //
    public final TrcEncoder lfSteerEncoder, rfSteerEncoder, lbSteerEncoder, rbSteerEncoder;
    public final TrcEncoder[] steerEncoders;
    public final TrcMotor lfSteerMotor, rfSteerMotor, lbSteerMotor, rbSteerMotor;
    public final TrcMotor[] steerMotors;
    public final TrcSwerveModule lfWheel, lbWheel, rfWheel, rbWheel;
    private String antiDefenseOwner = null;
    private boolean steerEncodersSynced = false;

    /**
     * Constructor: Create an instance of the object.
     *
     * @param robot specifies the robot object.
     */
    public SwerveDrive(Robot robot)
    {
        super(robot);

        // Steer zeros order: lf, rf, lb, rb.
        double[] zeros = getSteerZeroPositions();
        if (RobotParams.Preferences.useSteeringCANCoder)
        {
            lfSteerEncoder = createCANCoder(
                "lfSteerEncoder", RobotParams.CANID_LEFTFRONT_STEER_ENCODER, true, zeros[0]);
            rfSteerEncoder = createCANCoder(
                "rfSteerEncoder", RobotParams.CANID_RIGHTFRONT_STEER_ENCODER, true, zeros[1]);
            lbSteerEncoder = createCANCoder(
                "lbSteerEncoder", RobotParams.CANID_LEFTBACK_STEER_ENCODER, true, zeros[2]);
            rbSteerEncoder = createCANCoder(
                "rbSteerEncoder", RobotParams.CANID_RIGHTBACK_STEER_ENCODER, true, zeros[3]);
        }
        else if (RobotParams.Preferences.useSteeringAnalogEncoder)
        {
            lfSteerEncoder = createAnalogEncoder(
                "lfSteerEncoder", RobotParams.AIN_LEFTFRONT_STEER_ENCODER, true, zeros[0]);
            rfSteerEncoder = createAnalogEncoder(
                "rfSteerEncoder", RobotParams.AIN_RIGHTFRONT_STEER_ENCODER, true, zeros[1]);
            lbSteerEncoder = createAnalogEncoder(
                "lbSteerEncoder", RobotParams.AIN_LEFTBACK_STEER_ENCODER, true, zeros[2]);
            rbSteerEncoder = createAnalogEncoder(
                "rbSteerEncoder", RobotParams.AIN_RIGHTBACK_STEER_ENCODER, true, zeros[3]);
        }
        else
        {
            throw new IllegalArgumentException("Must enable either useCANCoder or useAnalogEncoder.");
        }
        steerEncoders = new TrcEncoder[] {lfSteerEncoder, rfSteerEncoder, lbSteerEncoder, rbSteerEncoder};

        lfSteerMotor = createMotor(
            RobotParams.STEER_MOTOR_TYPE, RobotParams.STEER_MOTOR_IS_BRUSHLESS,
            "lfSteer", RobotParams.CANID_LEFTFRONT_STEER, false);
        rfSteerMotor = createSteerMotor(
            RobotParams.STEER_MOTOR_TYPE, RobotParams.STEER_MOTOR_IS_BRUSHLESS,
            "rfSteer", RobotParams.CANID_RIGHTFRONT_STEER, false);
        lbSteerMotor = createSteerMotor(
            RobotParams.STEER_MOTOR_TYPE, RobotParams.STEER_MOTOR_IS_BRUSHLESS,
            "lbSteer", RobotParams.CANID_LEFTBACK_STEER, false);
        rbSteerMotor = createSteerMotor(
            RobotParams.STEER_MOTOR_TYPE, RobotParams.STEER_MOTOR_IS_BRUSHLESS,
            "rbSteer", RobotParams.CANID_RIGHTBACK_STEER, false);

        steerMotors = new TrcMotor[] {lfSteerMotor, rfSteerMotor, lbSteerMotor, rbSteerMotor};

        lfWheel = createSwerveModule("lfWheel", lfDriveMotor, lfSteerMotor, lfSteerEncoder);
        rfWheel = createSwerveModule("rfWheel", rfDriveMotor, rfSteerMotor, rfSteerEncoder);
        lbWheel = createSwerveModule("lbWheel", lbDriveMotor, lbSteerMotor, lbSteerEncoder);
        rbWheel = createSwerveModule("rbWheel", rbDriveMotor, rbSteerMotor, rbSteerEncoder);

        driveBase = new TrcSwerveDriveBase(
            lfWheel, lbWheel, rfWheel, rbWheel, gyro,
            RobotParams.ROBOT_WHEELBASE_WIDTH, RobotParams.ROBOT_WHEELBASE_LENGTH);
        driveBase.setOdometryScales(RobotParams.SWERVE_INCHES_PER_COUNT, RobotParams.SWERVE_INCHES_PER_COUNT);

        if (RobotParams.Preferences.useAntiTipping)
        {
            driveBase.enableAntiTipping(
                new TrcPidController.PidParameters(
                    RobotParams.X_TIPPING_KP, RobotParams.X_TIPPING_KI, RobotParams.X_TIPPING_KD,
                    RobotParams.X_TIPPING_TOLERANCE, this::getGyroRoll),
                new TrcPidController.PidParameters(
                    RobotParams.Y_TIPPING_KP, RobotParams.Y_TIPPING_KI, RobotParams.Y_TIPPING_KD,
                    RobotParams.Y_TIPPING_TOLERANCE, this::getGyroPitch));
        }
        // if (RobotParams.Preferences.useExternalOdometry)
        // {
        //     //
        //     // Create the external odometry device that uses the right back encoder port as the X odometry and
        //     // the left and right front encoder ports as the Y1 and Y2 odometry. Gyro will serve as the angle
        //     // odometry.
        //     //
        //     TrcDriveBaseOdometry driveBaseOdometry = new TrcDriveBaseOdometry(
        //         new TrcDriveBaseOdometry.AxisSensor(rbDriveMotor, RobotParams.X_ODOMETRY_WHEEL_OFFSET),
        //         new TrcDriveBaseOdometry.AxisSensor[] {
        //             new TrcDriveBaseOdometry.AxisSensor(lfDriveMotor, RobotParams.Y_LEFT_ODOMETRY_WHEEL_OFFSET),
        //             new TrcDriveBaseOdometry.AxisSensor(rfDriveMotor, RobotParams.Y_RIGHT_ODOMETRY_WHEEL_OFFSET)},
        //         gyro);
        //     //
        //     // Set the drive base to use the external odometry device overriding the built-in one.
        //     //
        //     driveBase.setDriveBaseOdometry(driveBaseOdometry);
        //     driveBase.setOdometryScales(RobotParams.ODWHEEL_X_INCHES_PER_COUNT, RobotParams.ODWHEEL_Y_INCHES_PER_COUNT);
        // }
        // else
        // {
        //     driveBase.setOdometryScales(RobotParams.SWERVE_INCHES_PER_COUNT);
        // }

        if (robot.pdp != null)
        {
            robot.pdp.registerEnergyUsed(
                new FrcPdp.Channel(RobotParams.PDP_CHANNEL_LEFT_FRONT_DRIVE, "lfDriveMotor"),
                new FrcPdp.Channel(RobotParams.PDP_CHANNEL_LEFT_BACK_DRIVE, "lbDriveMotor"),
                new FrcPdp.Channel(RobotParams.PDP_CHANNEL_RIGHT_FRONT_DRIVE, "rfDriveMotor"),
                new FrcPdp.Channel(RobotParams.PDP_CHANNEL_RIGHT_BACK_DRIVE, "rbDriveMotor"),
                new FrcPdp.Channel(RobotParams.PDP_CHANNEL_LEFT_FRONT_STEER, "lfSteerMotor"),
                new FrcPdp.Channel(RobotParams.PDP_CHANNEL_LEFT_BACK_STEER, "lbSteerMotor"),
                new FrcPdp.Channel(RobotParams.PDP_CHANNEL_RIGHT_FRONT_STEER, "rfSteerMotor"),
                new FrcPdp.Channel(RobotParams.PDP_CHANNEL_RIGHT_BACK_STEER, "rbSteerMotor"));
        }
        //
        // Create and initialize PID controllers.
        //
        // PID Parameters for X and Y are the same for Swerve Drive.
        xPosPidCoeff = yPosPidCoeff = new TrcPidController.PidCoefficients(
            RobotParams.SWERVE_KP, RobotParams.SWERVE_KI, RobotParams.SWERVE_KD, RobotParams.SWERVE_KF, RobotParams.SWERVE_IZONE);
        turnPidCoeff = new TrcPidController.PidCoefficients(
            RobotParams.GYRO_TURN_KP, RobotParams.GYRO_TURN_KI, RobotParams.GYRO_TURN_KD, RobotParams.GYRO_TURN_KF,
            RobotParams.GYRO_TURN_IZONE);
        velPidCoeff = new TrcPidController.PidCoefficients(
            RobotParams.ROBOT_VEL_KP, RobotParams.ROBOT_VEL_KI, RobotParams.ROBOT_VEL_KD, RobotParams.ROBOT_VEL_KF);

        pidDrive = new TrcPidDrive(
            "pidDrive", driveBase,
            new TrcPidController.PidParameters(xPosPidCoeff, RobotParams.SWERVE_TOLERANCE, driveBase::getXPosition),
            new TrcPidController.PidParameters(yPosPidCoeff, RobotParams.SWERVE_TOLERANCE, driveBase::getYPosition),
            new TrcPidController.PidParameters(turnPidCoeff, RobotParams.GYRO_TURN_TOLERANCE, driveBase::getHeading));

        TrcPidController xPidCtrl = pidDrive.getXPidCtrl();
        xPidCtrl.setOutputLimit(RobotParams.DRIVE_MAX_XPID_POWER);
        xPidCtrl.setRampRate(RobotParams.DRIVE_MAX_XPID_RAMP_RATE);

        TrcPidController yPidCtrl = pidDrive.getYPidCtrl();
        yPidCtrl.setOutputLimit(RobotParams.DRIVE_MAX_YPID_POWER);
        yPidCtrl.setRampRate(RobotParams.DRIVE_MAX_YPID_RAMP_RATE);

        TrcPidController turnPidCtrl = pidDrive.getTurnPidCtrl();
        turnPidCtrl.setOutputLimit(RobotParams.DRIVE_MAX_TURNPID_POWER);
        turnPidCtrl.setRampRate(RobotParams.DRIVE_MAX_TURNPID_RAMP_RATE);
        turnPidCtrl.setAbsoluteSetPoint(true);

        // AbsoluteTargetMode eliminates cumulative errors on multi-segment runs because drive base is keeping track
        // of the absolute target position.
        pidDrive.setAbsoluteTargetModeEnabled(true);
        pidDrive.setMsgTracer(robot.globalTracer, logPoseEvents, tracePidInfo);

        purePursuitDrive = new TrcPurePursuitDrive(
            "purePursuitDrive", driveBase, RobotParams.PPD_FOLLOWING_DISTANCE, RobotParams.PPD_POS_TOLERANCE,
            RobotParams.PPD_TURN_TOLERANCE, xPosPidCoeff, yPosPidCoeff, turnPidCoeff, velPidCoeff);
        purePursuitDrive.setMoveOutputLimit(RobotParams.PPD_MOVE_DEF_OUTPUT_LIMIT);
        purePursuitDrive.setRotOutputLimit(RobotParams.PPD_ROT_DEF_OUTPUT_LIMIT);
        purePursuitDrive.setFastModeEnabled(true);
        purePursuitDrive.setMsgTracer(robot.globalTracer, logPoseEvents, tracePidInfo);
    }   //SwerveDrive

    /**
     * This method creates an encoder for the steering motor.
     *
     * @param name specifies the instance name of the steering encoder.
     * @param encoderId specifies the CAN ID of the CANcoder.
     * @param inverted specifies true if the sensor direction should be inverted, false otherwise.
     * @param steerZero specifies the zero position.
     * @return the created steering encoder.
     */
    private TrcEncoder createCANCoder(String name, int encoderId, boolean inverted, double steerZero)
    {
        final String funcName = "createCANcoder";
        TrcEncoder encoder = new FrcCANCoder(name, encoderId);

        CANCoder canCoder = (CANCoder) encoder;
        ErrorCode errCode;
        // Reset encoder back to factory default to clear potential previous mis-configurations.
        errCode = canCoder.configFactoryDefault(30);
        if (errCode != ErrorCode.OK)
        {
            robot.globalTracer.traceWarn(
                funcName, "%s: CANcoder.configFactoryDefault failed (code=%s).",
                name, errCode);
        }
        errCode = canCoder.configFeedbackCoefficient(1.0, "cpr", SensorTimeBase.PerSecond, 30);
        if (errCode != ErrorCode.OK)
        {
            robot.globalTracer.traceWarn(
                funcName, "%s: CANcoder.configFeedbackCoefficient failed (code=%s).",
                name, errCode);
        }
        // Configure the encoder to initialize to absolute position value at boot.
        errCode = canCoder.configSensorInitializationStrategy(SensorInitializationStrategy.BootToAbsolutePosition, 30);
        if (errCode != ErrorCode.OK)
        {
            robot.globalTracer.traceWarn(
                funcName, "%s: CANcoder.configSensorInitializationStrategy failed (code=%s).",
                name, errCode);
        }
        // Slow down the status frame rate to reduce CAN traffic.
        errCode = canCoder.setStatusFramePeriod(CANCoderStatusFrame.SensorData, 100, 30);
        if (errCode != ErrorCode.OK)
        {
            robot.globalTracer.traceWarn(
                funcName, "%s: CANcoder.setStatusFramePeriod failed (code=%s).",
                name, errCode);
        }
        // Configure the sensor direction to match the steering motor direction.
        encoder.setInverted(inverted);
        // Normalize encoder to the range of 0 to 1.0 for a revolution (revolution per count).
        encoder.setScaleAndOffset(1.0 / RobotParams.CANCODER_CPR, steerZero);

        return encoder;
    }   //createCANCoder

    /**
     * This method creates an encoder for the steering motor.
     *
     * @param name specifies the instance name of the steering encoder.
     * @param encoderId specifies the analog channel of the analog encoder.
     * @param inverted specifies true if the sensor direction should be inverted, false otherwise.
     * @param steerZero specifies the zero position.
     * @return the created steering encoder.
     */
    private TrcEncoder createAnalogEncoder(String name, int encoderId, boolean inverted, double steerZero)
    {
        TrcEncoder encoder = new FrcAnalogEncoder(name, encoderId);

        encoder.setInverted(inverted);
        // Analog Encoder is already normalized to the range of 0 to 1.0 for a revolution (revolution per count).
        encoder.setScaleAndOffset(1.0, steerZero);
        return encoder;
    }   //createAnalogEncoder

    /**
     * This method creates a steering motor for a swerve module.
     *
     * @param motorType specifies the drive motor type.
     * @param brushless specifies true if drive motor is brushless, false if brushed (only applicable SparkMax).
     * @param name specifies the instance name of the steering motor.
     * @param motorCanID specifies the CAN ID of the steering motor.
     * @param inverted specifies true if the steering motor should be inverted, false otherwise.
     * @return the created steering motor.
     */
    private TrcMotor createSteerMotor(
        MotorType motorType, boolean brushless, String name, int motorCanID, boolean inverted)
    {
        TrcMotor steerMotor = null;

        if (motorType == MotorType.CAN_FALCON)
        {
            steerMotor = new FrcCANFalcon(name, motorCanID);
        }
        else if (motorType == MotorType.CAN_TALON)
        {
            steerMotor = new FrcCANTalon(name, motorCanID);
        }
        else if (motorType == MotorType.CAN_SPARKMAX)
        {
            steerMotor = new FrcCANSparkMax(name, motorCanID, brushless);
        }

        steerMotor.resetFactoryDefault();
        steerMotor.setVoltageCompensationEnabled(RobotParams.BATTERY_NOMINAL_VOLTAGE);
        steerMotor.setMotorInverted(inverted);
        steerMotor.setBrakeModeEnabled(true);

        return steerMotor;
    }   //createSteerMotor

    /**
     * This method creates the swerve module that consists of a driving motor and a steering motor.
     *
     * @param name specifies the swerve module instance name.
     * @param driveMotor specifies the drive motor object.
     * @param steerMotor specifies the steering motor object.
     * @param steerEncoder specifies the steering encoder object.
     * @return the created swerve module.
     */
    private TrcSwerveModule createSwerveModule(
        String name, TrcMotor driveMotor, TrcMotor steerMotor, TrcEncoder steerEncoder)
    {
        final String funcName = "createSwerveModule";
        // getPosition returns a value in the range of 0 to 1.0 of one revolution.
        double encoderPos = steerEncoder.getPosition();

        encoderPos *= RobotParams.STEER_MOTOR_CPR;
        ErrorCode errCode = ((FrcCANFalcon) steerMotor).motor.setSelectedSensorPosition(encoderPos, 0, 30);
        if (errCode != ErrorCode.OK)
        {
            robot.globalTracer.traceWarn(
                funcName, "%s: Falcon.setSelectedSensorPosition failed (code=%s, pos=%.0f).",
                name, errCode, encoderPos);
        }

        // We have already synchronized the Falcon internal encoder with the zero adjusted absolute encoder, so
        // Falcon servo does not need to compensate for zero position.
        FrcFalconServo servo = new FrcFalconServo(
            name + ".servo", (FrcCANFalcon) steerMotor, RobotParams.steerCoeffs, RobotParams.STEER_DEGREES_PER_COUNT, 0.0,
            RobotParams.STEER_MAX_REQ_VEL, RobotParams.STEER_MAX_ACCEL);
        servo.setControlMode(TalonFXControlMode.MotionMagic);
        servo.setPhysicalRange(0.0, 360.0);
        TrcSwerveModule module = new TrcSwerveModule(name, driveMotor, servo);
        module.disableSteeringLimits();

        return module;
    }   //createSwerveModule

    /**
     * This method displays the steering absolute encoder and internal motor encoder values on the dashboard for
     * debuggig purpose.
     *
     * @param lineNum specifies the starting line number on the dashboard to display the info.
     */
    public void displaySteerEncoders(int lineNum)
    {
        double lfSteerAbsEnc = lfSteerEncoder.getPosition()*360.0;
        // if (lfSteerAbsEnc > 90.0) lfSteerAbsEnc = 180.0 - lfSteerAbsEnc;
        double rfSteerAbsEnc = rfSteerEncoder.getPosition()*360.0;
        // if (rfSteerAbsEnc > 90.0) rfSteerAbsEnc = 180.0 - rfSteerAbsEnc;
        double lbSteerAbsEnc = lbSteerEncoder.getPosition()*360.0;
        // if (lbSteerAbsEnc > 90.0) lbSteerAbsEnc = 180.0 - lbSteerAbsEnc;
        double rbSteerAbsEnc = rbSteerEncoder.getPosition()*360.0;
        // if (rbSteerAbsEnc > 90.0) rbSteerAbsEnc = 180.0 - rbSteerAbsEnc;
        double lfSteerEnc = (lfSteerMotor.getMotorPosition() % RobotParams.STEER_MOTOR_CPR) /
                            RobotParams.STEER_MOTOR_CPR * 360.0;
        double rfSteerEnc = (rfSteerMotor.getMotorPosition() % RobotParams.STEER_MOTOR_CPR) /
                            RobotParams.STEER_MOTOR_CPR * 360.0;
        double lbSteerEnc = (lbSteerMotor.getMotorPosition() % RobotParams.STEER_MOTOR_CPR) /
                            RobotParams.STEER_MOTOR_CPR * 360.0;
        double rbSteerEnc = (rbSteerMotor.getMotorPosition() % RobotParams.STEER_MOTOR_CPR) /
                            RobotParams.STEER_MOTOR_CPR * 360.0;

        robot.dashboard.displayPrintf(
            lineNum, "SteerEnc: lf=%6.1f/%6.1f, rf=%6.1f/%6.1f, lb=%6.1f/%6.1f, rb=%6.1f/%6.1f",
            lfSteerAbsEnc, lfSteerEnc, rfSteerAbsEnc, rfSteerEnc,
            lbSteerAbsEnc, lbSteerEnc, rbSteerAbsEnc, rbSteerEnc);
        lineNum++;
        robot.dashboard.displayPrintf(
            lineNum, "SteerErr: lf=%6.3f, rf=%6.3f, lb=%6.3f, rb=%6.3f",
            lfSteerAbsEnc - lfSteerEnc, rfSteerAbsEnc - rfSteerEnc,
            lbSteerAbsEnc - lbSteerEnc, rbSteerAbsEnc - rbSteerEnc);
        lineNum++;

        robot.dashboard.putNumber("Graphs/lfSteerAbsPos", lfSteerAbsEnc);
        robot.dashboard.putNumber("Graphs/rfSteerAbsPos", rfSteerAbsEnc);
        robot.dashboard.putNumber("Graphs/lbSteerAbsPos", lbSteerAbsEnc);
        robot.dashboard.putNumber("Graphs/rbSteerAbsPos", rbSteerAbsEnc);
        robot.dashboard.putNumber("Graphs/lfSteerPos", lfSteerEnc);
        robot.dashboard.putNumber("Graphs/rfSteerPos", rfSteerEnc);
        robot.dashboard.putNumber("Graphs/lbSteerPos", lbSteerEnc);
        robot.dashboard.putNumber("Graphs/rbSteerPos", rbSteerEnc);
    }   //displaySteerEncoders

    /**
     * This method is called to set all swerve wheels to zero degrees.
     *
     * @param optimize specifies true to optimize the shortest way to point the wheels forward, could end up at
     *        180-degree instead of zero, false to set wheel angle to absolute zero.
     */
    public void setSteerAngleZero(boolean optimize)
    {
        lfWheel.setSteerAngle(0.0, optimize);
        rfWheel.setSteerAngle(0.0, optimize);
        lbWheel.setSteerAngle(0.0, optimize);
        rbWheel.setSteerAngle(0.0, optimize);
    }   //setSteerAngleZero

    /**
     * This method checks if the steer motor internal encoders are in sync with the absolute encoders. If not, it will
     * do a re-sync of the steer motor encoders to the absolute enocder posiitions. This method can be called multiple
     * times but it will only perform the re-sync the first time it's called unless forceSync is set to true.
     *
     * @param forceSync specifies true to force performing the encoder resync, false otherwise.
     */
    public void syncSteerEncoders(boolean forceSync)
    {
        final String funcName = "syncSteerEncoders";
        final double encErrThreshold = 20.0;
        final double timeout = 0.5;

        if (!steerEncodersSynced || forceSync)
        {
            final Watchdog watchdog = TrcWatchdogMgr.getWatchdog();
            double expiredTime = TrcTimer.getCurrentTime() + timeout;
            boolean onTarget = false;

            watchdog.pauseWatch();
            setSteerAngleZero(false);
            TrcTimer.sleep(200);
            while (!onTarget && TrcTimer.getCurrentTime() < expiredTime)
            {
                onTarget = true;
                for (int i = 0; i < steerMotors.length; i++)
                {
                    double steerPos = steerMotors[i].getMotorPosition();
                    if (Math.abs(steerPos) > encErrThreshold)
                    {
                        robot.globalTracer.traceInfo(
                            funcName, "[%.3f] steerEncPos[%d]=%.0f", TrcTimer.getModeElapsedTime(), i, steerPos);
                        onTarget = false;
                        break;
                    }
                }

                if (!onTarget)
                {
                    Thread.yield();
                }
            }

            if (!onTarget)
            {
                for (int i = 0; i < steerMotors.length; i++)
                {
                    double encoderPos = steerEncoders[i].getPosition() * RobotParams.STEER_MOTOR_CPR;
                    ((FrcCANFalcon) steerMotors[i]).motor.setSelectedSensorPosition(encoderPos, 0, 0);
                    robot.globalTracer.traceInfo(
                        funcName, "[%.3f] syncSteerEncPos[%d]=%.0f", TrcTimer.getModeElapsedTime(), i, encoderPos);
            }
            }

            steerEncodersSynced = true;
        }
    }   //syncSteerEncoders

    /**
     * This method is called to prepare the robot base before a robot mode is about to start.
     *
     * @param runMode specifies the current run mode.
     * @param prevMode specifies the previous run mode.
     */
    @Override
    public void startMode(RunMode runMode, RunMode prevMode)
    {
        super.startMode(runMode, prevMode);
        // Set all swerve steering pointing to absolute forward to start.
        if (runMode != RunMode.TEST_MODE && runMode != RunMode.DISABLED_MODE)
        {
            setSteerAngleZero(false);
            syncSteerEncoders(false);
        }
    }   //startMode

    /**
     * This method is called to prepare the robot base right after a robot mode has been stopped.
     *
     * @param runMode specifies the current run mode.
     * @param nextMode specifies the next run mode.
     */
    @Override
    public void stopMode(RunMode runMode, RunMode nextMode)
    {
        super.stopMode(runMode, nextMode);
        // setSteerAngleZero(false);
    }   //stopMode

    /**
     * This method retrieves the steering zero calibration data from the calibration data file.
     *
     * @return calibration data of all four swerve modules.
     */
    public static double[] getSteerZeroPositions()
    {
        final String funcName = "getSteerZeroPositions";

        try (Scanner in = new Scanner(new FileReader(RobotParams.TEAM_FOLDER + "/" + STEER_ZERO_CAL_FILE)))
        {
            double[] steerZeros = new double[4];

            for (int i = 0; i < steerZeros.length; i++)
            {
                steerZeros[i] = in.nextDouble();
            }

            return steerZeros;
        }
        catch (Exception e)
        {
            TrcDbgTrace.globalTraceWarn(funcName, "Steer zero position file not found, using built-in defaults.");
            return RobotParams.STEER_ZEROS;
        }
    }   //getSteerZeroPositions

    /**
     * This method saves the steering zero calibration data to the calibration data file.
     *
     * @param steerZeros specifies the steering zero calibration data to be saved.
     */
    public static void saveSteerZeroPositions(double[] steerZeros)
    {
        final String funcName = "saveSteerZeroPositions";

        try (PrintStream out = new PrintStream(new FileOutputStream(RobotParams.TEAM_FOLDER + "/" + STEER_ZERO_CAL_FILE)))
        {
            for (double zero : steerZeros)
            {
                out.printf("%f\n", zero);
            }
            TrcDbgTrace.globalTraceInfo(funcName, "Saved steer zeros: %s!", Arrays.toString(steerZeros));
        }
        catch (FileNotFoundException e)
        {
            e.printStackTrace();
        }
    }   //saveSteerZeroPositions

    /**
     * This method checks if anti-defense mode is enabled.
     *
     * @return true if anti-defense mode is enabled, false if disabled.
     */
    public boolean isAntiDefenseEnabled()
    {
        return ((TrcSwerveDriveBase) driveBase).isAntiDefenseEnabled();
    }   //isAntiDefenseEnabled

    /**
     * This method enables/disables the anti-defense mode where it puts all swerve wheels into an X-formation.
     * By doing so, it is very difficult for others to push us around.
     *
     * @param owner     specifies the ID string of the caller for checking ownership, can be null if caller is not
     *                  ownership aware.
     * @param enabled   specifies true to enable anti-defense mode, false to disable.
     */
    public void setAntiDefenseEnabled(String owner, boolean enabled)
    {
        boolean requireOwnership = owner != null && enabled && !driveBase.hasOwnership(owner);

        if (requireOwnership && driveBase.acquireExclusiveAccess(owner))
        {
            antiDefenseOwner = owner;
        }

        if (!requireOwnership || antiDefenseOwner != null)
        {
            ((TrcSwerveDriveBase) driveBase).setAntiDefenseEnabled(owner, enabled);
            if (antiDefenseOwner != null)
            {
                driveBase.releaseExclusiveAccess(antiDefenseOwner);
                antiDefenseOwner = null;
            }
        }
    }   //setAntiDefenseEnabled

}   //class SwerveDrive
