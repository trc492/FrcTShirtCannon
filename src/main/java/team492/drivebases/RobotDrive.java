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
import java.util.Scanner;

import com.ctre.phoenix.ErrorCode;

import TrcCommonLib.trclib.TrcDriveBase;
import TrcCommonLib.trclib.TrcGyro;
import TrcCommonLib.trclib.TrcPidController;
import TrcCommonLib.trclib.TrcPidDrive;
import TrcCommonLib.trclib.TrcPose2D;
import TrcCommonLib.trclib.TrcPurePursuitDrive;
import TrcCommonLib.trclib.TrcUtil;
import TrcCommonLib.trclib.TrcRobot.RunMode;
import TrcFrcLib.frclib.FrcAHRSGyro;
import TrcFrcLib.frclib.FrcCANFalcon;
import edu.wpi.first.wpilibj.SPI;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import team492.FrcAuto;
import team492.Robot;
import team492.RobotParams;

/**
 * This class is intended to be extended by subclasses implementing different robot drive bases.
 */
public class RobotDrive
{
    private static final String FIELD_ZERO_HEADING_FILE = "FieldZeroHeading.txt";

    public enum DriveOrientation
    {
        ROBOT, FIELD, INVERTED
    }   //enum DriveOrientation
    //
    // Global objects.
    //
    protected final Robot robot;
    //
    // Sensors.
    //
    public final TrcGyro gyro;
    //
    // Drive motors.
    //
    public FrcCANFalcon lfDriveMotor, lbDriveMotor, rfDriveMotor, rbDriveMotor;
    //
    // Drive Base.
    //
    public TrcDriveBase driveBase;
    //
    // PID Coefficients.
    //
    public TrcPidController.PidCoefficients xPosPidCoeff, yPosPidCoeff, turnPidCoeff, velPidCoeff;
    public TrcPidController.PidCoefficients gyroPitchPidCoeff;      // for anti-tipping.
    //
    // Drive Controllers.
    //
    public TrcPidDrive pidDrive;
    public TrcPurePursuitDrive purePursuitDrive;
    //
    // Miscellaneous.
    //
    public DriveOrientation driveOrientation = DriveOrientation.FIELD;
    public double driveSpeedScale = RobotParams.DRIVE_FAST_SCALE;
    public double turnSpeedScale = RobotParams.TURN_FAST_SCALE;
    //
    // Odometry.
    //
    private TrcPose2D endOfAutoRobotPose = null;

    /**
     * Constructor: Create an instance of the object.
     *
     * @param robot specifies the robot object.
     */
    public RobotDrive(Robot robot)
    {
        this.robot = robot;
        gyro = RobotParams.Preferences.useNavX ? new FrcAHRSGyro("NavX", SPI.Port.kMXP) : null;
    }   //RobotDrive

    /**
     * This method is called to prepare the robot base before a robot mode is about to start.
     *
     * @param runMode specifies the current run mode.
     * @param prevMode specifies the previous run mode.
     */
    public void startMode(RunMode runMode, RunMode prevMode)
    {
        if (runMode != RunMode.DISABLED_MODE)
        {
            driveSpeedScale = RobotParams.DRIVE_FAST_SCALE;
            turnSpeedScale = RobotParams.TURN_FAST_SCALE;
            driveBase.setOdometryEnabled(true, true);

            if (runMode == RunMode.AUTO_MODE)
            {
                // Disable ramp rate control in autonomous.
                lfDriveMotor.motor.configOpenloopRamp(0.0);
                rfDriveMotor.motor.configOpenloopRamp(0.0);
                if (lbDriveMotor != null)
                {
                    lbDriveMotor.motor.configOpenloopRamp(0.0);
                }
                if (rbDriveMotor != null)
                {
                    rbDriveMotor.motor.configOpenloopRamp(0.0);
                }
            }
            else
            {
                lfDriveMotor.motor.configOpenloopRamp(RobotParams.DRIVE_RAMP_RATE);
                rfDriveMotor.motor.configOpenloopRamp(RobotParams.DRIVE_RAMP_RATE);
                if (lbDriveMotor != null)
                {
                    lbDriveMotor.motor.configOpenloopRamp(RobotParams.DRIVE_RAMP_RATE);
                }
                if (rbDriveMotor != null)
                {
                    rbDriveMotor.motor.configOpenloopRamp(RobotParams.DRIVE_RAMP_RATE);
                }

                if (runMode == RunMode.TELEOP_MODE && endOfAutoRobotPose != null)
                {
                    driveBase.setFieldPosition(endOfAutoRobotPose);
                    endOfAutoRobotPose = null;
                }

                if (RobotParams.Preferences.useGyroAssist)
                {
                    driveBase.enableGyroAssist(RobotParams.ROBOT_MAX_TURN_RATE, RobotParams.GYRO_ASSIST_TURN_GAIN);
                }
            }
        }
    }   //startMode

    /**
     * This method is called to prepare the robot base right after a robot mode has been stopped.
     *
     * @param runMode specifies the current run mode.
     * @param nextMode specifies the next run mode.
     */
    public void stopMode(RunMode runMode, RunMode nextMode)
    {
        if (runMode != RunMode.DISABLED_MODE)
        {
            cancel();

            if (runMode == RunMode.AUTO_MODE)
            {
                endOfAutoRobotPose = driveBase.getFieldPosition();
            }
            driveBase.setOdometryEnabled(false);
        }
    }   //stopMode

    /**
     * This method cancels any PIDDrive operation still in progress.
     *
     * @param owner specifies the owner that requested the cancel.
     */
    public void cancel(String owner)
    {
        if (pidDrive != null && pidDrive.isActive())
        {
            pidDrive.cancel(owner);
        }

        if (purePursuitDrive != null && purePursuitDrive.isActive())
        {
            purePursuitDrive.cancel(owner);
        }

        driveBase.stop(owner);
    }   //cancel

    /**
     * This method cancels any PIDDrive operation still in progress.
     */
    public void cancel()
    {
        cancel(null);
    }   //cancel

    /**
     * This method create a drive motor and configure it.
     *
     * @param name specifies the name of the drive motor.
     * @param motorCanID specifies the CAN ID of the drive motor.
     * @param inverted specifies true to invert the drive motor, false otherwise.
     */
    protected FrcCANFalcon createDriveMotor(String name, int motorCanID, boolean inverted)
    {
        final String funcName = "createDriveMotor";
        FrcCANFalcon driveMotor = new FrcCANFalcon(name, motorCanID);
        ErrorCode errCode;

        errCode = driveMotor.motor.configFactoryDefault(10);
        if (errCode != ErrorCode.OK)
        {
            robot.globalTracer.traceWarn(
                funcName, "%s: Falcon.configFactoryDefault failed (code=%s).",
                name, errCode);
        }

        errCode = driveMotor.motor.configVoltageCompSaturation(RobotParams.BATTERY_NOMINAL_VOLTAGE, 10);
        if (errCode != ErrorCode.OK)
        {
            robot.globalTracer.traceWarn(
                funcName, "%s: Falcon.configVoltageCompSaturation failed (code=%s).",
                name, errCode);
        }

        driveMotor.motor.enableVoltageCompensation(true);
        driveMotor.setMotorInverted(inverted);
        // Drive motor should always be in brake mode.
        driveMotor.setBrakeModeEnabled(true);

        return driveMotor;
    }   //createDriveMotor

    /**
     * This method reads various joystick/gamepad control values and returns the drive powers for all three degrees
     * of robot movement.
     *
     * @return an array of 3 values for x, y and rotation power.
     */
    public double[] getDriveInputs()
    {
        double x, y, rot;
        double mag;
        double newMag;

        if (RobotParams.Preferences.useDriverXboxController)
        {
            x = robot.driverController.getLeftXWithDeadband(false);
            y = robot.driverController.getLeftYWithDeadband(false);
            rot = robot.driverController.getRightXWithDeadband(true);
        }
        else
        {
            x = robot.rightDriveStick.getXWithDeadband(false);
            y = robot.rightDriveStick.getYWithDeadband(false);
            if(RobotParams.Preferences.doOneStickDrive)
            {
                rot = robot.rightDriveStick.getTwistWithDeadband(true);
            }
            else
            {
                rot = robot.leftDriveStick.getXWithDeadband(true);
            }
        }
        // Apply squared or cubic curve to the X/Y drive.
        mag = TrcUtil.magnitude(x, y);
        if (mag > 1.0)
        {
            x /= mag;
            y /= mag;
            mag = 1.0;
        }
        newMag = Math.pow(mag, RobotParams.Preferences.useDriverXboxController? 3: 2);
        newMag *= driveSpeedScale;
        rot *= turnSpeedScale;

        if (mag != 0.0)
        {
            x *= newMag / mag;
            y *= newMag / mag;
        }

        return new double[] { x, y, rot };
    }   //getDriveInput

    /**
     * This method returns robot heading to be maintained in teleop drive according to drive orientation mode.
     *
     * @return robot heading to be maintained.
     */
    public double getDriveGyroAngle()
    {
        switch (driveOrientation)
        {
            case ROBOT:
                return 0.0;

            case INVERTED:
                return 180.0;

            default:
            case FIELD:
                return robot.robotDrive.driveBase.getHeading();
        }
    }   //getDriveGyroAngle

    /**
     * This method sets the drive orientation mode and updates the LED state correspondingly.
     */
    public void setDriveOrientation(DriveOrientation orientation)
    {
        driveOrientation = orientation;
        robot.ledIndicator.setDriveOrientation(driveOrientation);
        // If switching to FIELD oriented driving, reset robot heading so that the current robot heading is "north".
        if (driveOrientation == DriveOrientation.FIELD)
        {
            TrcPose2D robotPose = robot.robotDrive.driveBase.getFieldPosition();
            robotPose.angle = 0.0;
            robot.robotDrive.driveBase.setFieldPosition(robotPose);
        }
    }   //setDriveOrientation

    /**
     * This method saves the compass heading value when the robot is facing field zero.
     */
    public void saveFieldZeroCompassHeading()
    {
        final String funcName = "saveFieldZeroCompassHeading";

        try (PrintStream out = new PrintStream(
                new FileOutputStream(RobotParams.TEAM_FOLDER + "/" + FIELD_ZERO_HEADING_FILE)))
        {
            double fieldZeroHeading = ((FrcAHRSGyro) gyro).ahrs.getCompassHeading();

            out.printf("%f\n", fieldZeroHeading);
            out.close();
            robot.globalTracer.traceInfo(funcName, "FieldZeroCompassHeading = %f", fieldZeroHeading);
        }
        catch (FileNotFoundException e)
        {
            e.printStackTrace();
        }
    }   //saveFieldZeroCompassHeading

    /**
     * This method retrieves the field zero compass heading from the calibration data file.
     *
     * @return calibration data of field zero compass heading.
     */
    private Double getFieldZeroCompassHeading()
    {
        final String funcName = "getFieldZeroCompassHeading";

        try (Scanner in = new Scanner(new FileReader(RobotParams.TEAM_FOLDER + "/" + FIELD_ZERO_HEADING_FILE)))
        {
            return in.nextDouble();
        }
        catch (Exception e)
        {
            robot.globalTracer.traceWarn(funcName, "FieldZeroHeading file not found.");
            return null;
        }
    }   //getFieldZeroHeading

    /**
     * This method sets the robot's absolute field position. This is typically called at the beginning of a match for
     * robot localization. The provided pose should be the robot's starting position. If null, it will try to get the
     * robot start pose from the auto choices on the dashboard. Optionally, the caller can set useCompassHeading to
     * true for using compass heading to determine the true robot heading. This only works if the robot has been
     * calibrated on the competition field for its field zero position.
     * Note: if reading the field zero calibration file failed, it will behave as if useCompassHeading is false.
     *
     * @param pose speicifies the robot's starting position on the field.
     * @param useCompassHeading specifies true to use compass to determine the robot's true heading, false otherwise.
     */
    public void setFieldPosition(TrcPose2D pose, boolean useCompassHeading)
    {
        TrcPose2D robotPose;

        if (pose == null)
        {
            int startPos = FrcAuto.autoChoices.getStartPos();
            robotPose = FrcAuto.autoChoices.getAlliance() == Alliance.Blue?
                RobotParams.startPos[0][startPos]: RobotParams.startPos[1][startPos];
        }
        else
        {
            robotPose = pose.clone();
        }

        if (useCompassHeading)
        {
            Double fieldZero = getFieldZeroCompassHeading();

            if (fieldZero != null)
            {
                robotPose.angle = ((FrcAHRSGyro) gyro).ahrs.getCompassHeading() - fieldZero;
            }
        }

        driveBase.setFieldPosition(robotPose);
    }   //setFieldPosition

    /**
     * This method sets the robot's absolute field position. This is typically called at the beginning of a match for
     * robot localization. The provided pose should be the robot's starting position. If null, it will try to get the
     * robot start pose from the auto choices on the dashboard. Optionally, the caller can set  useCompassHeading to
     * true for using compass heading to determine the true robot heading. This only works if the robot has been
     * calibrated on the competition field for its field zero position.
     * Note: if reading the field zero calibration file failed, it will behave as if useCompassHeading is false.
     *
     * @param useCompassHeading specifies true to use compass to determine the robot's true heading, false otherwise.
     */
    public void setFieldPosition(boolean useCompassHeading)
    {
        setFieldPosition(null, useCompassHeading);
    }   //setFieldPosition

    /**
     * This method returns the gyro pitch.
     *
     * @return gyro pitch.
     */
    public double getGyroPitch()
    {
        return gyro.getXHeading().value;
    }   //getGyroPitch

    /**
     * This method returns the gyro roll.
     *
     * @return gyro roll.
     */
    public double getGyroRoll()
    {
        return gyro.getYHeading().value;
    }   //getGyroRoll

    /**
     * This method returns an adjusted absolute position by the robot's alliance.
     *
     * @param alliance specifies the robot alliance.
     * @param pose specifies the absolute position for the blue alliance.
     * @return returns unchanged pos if blue alliance, adjusted to the opposite side if red alliance.
     */
    public TrcPose2D adjustPoseByAlliance(Alliance alliance, TrcPose2D pose)
    {
        if (alliance == Alliance.Red)
        {
            // no change on x, change y to the opposite side of the field.
            pose.y = RobotParams.FIELD_LENGTH - pose.y;
            pose.angle = (pose.angle + 180.0) % 360.0;
        }

        return pose;
    }   //adjustPoseByAlliance

}   //class RobotDrive
