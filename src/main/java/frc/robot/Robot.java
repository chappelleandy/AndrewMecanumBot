package frc.robot;

import com.ctre.phoenix.motorcontrol.can.WPI_TalonSRX;

import com.kauailabs.navx.frc.AHRS;

import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.*;
import edu.wpi.first.wpilibj.GenericHID.Hand;
import edu.wpi.first.wpilibj.drive.MecanumDrive;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;

public class Robot extends TimedRobot implements PIDOutput {

    Hand leftHand = GenericHID.Hand.kLeft;
    Hand rightHand = GenericHID.Hand.kRight;
    XboxController controller = new XboxController(0);
    //XboxController secondary = controller;
    XboxController secondary = new XboxController(1);

    Compressor compressor = new Compressor(61);

    AHRS ahrs = new AHRS(SPI.Port.kMXP);
    private static double kPTurn = 0.0105;
    private static double kITurn = 0.00;
    private static double kDTurn = 0.005;
    private static double kFTurn = 0.00;
    private static double kToleranceDegrees = 2.0f;

    private static double kP = 0.00;
    private static double kI = 0.00;
    private static double kD = 0.00;
    private static double kF = 0.00;

    private static boolean hadDoublePOV = false; // TODO: rename because a bit misleading
    private static boolean isFieldCentric = true;
    private static boolean armsUp = true;
    private static boolean intakeActive = false;
    private static boolean isHatchDown = false;

    //the centers of the two retro-reflective targets, if both the x and y of a target are zero it isn't detected
    private static double zeroCenterX, zeroCenterY, oneCenterX, oneCenterY;

    WPI_TalonSRX frontRight = new WPI_TalonSRX(2);
    WPI_TalonSRX backRight = new WPI_TalonSRX(4);
    WPI_TalonSRX frontLeft = new WPI_TalonSRX(1);
    WPI_TalonSRX backLeft = new WPI_TalonSRX(3);
    WPI_TalonSRX leftArm = new WPI_TalonSRX(5);
    WPI_TalonSRX rightArm = new WPI_TalonSRX(6);
    WPI_TalonSRX elevator = new WPI_TalonSRX(7);
    WPI_TalonSRX intake = new WPI_TalonSRX(8);

    Lifter lifter = new Lifter(elevator);

    DoubleSolenoid hatchDeploy = new DoubleSolenoid(61, 2,3);
    DoubleSolenoid armsDeploy = new DoubleSolenoid(61, 4,5);
    DoubleSolenoid hatchMechanism = new DoubleSolenoid(61,0,1);

    private static double rotateToAngleRate;

    MecanumDrive myDrive = new MecanumDrive(frontLeft, backLeft, frontRight, backRight);
    PIDController turnController = new PIDController(kPTurn, kITurn, kDTurn,kFTurn, ahrs, this);

    AutoStrafer autoStrafer = new AutoStrafer();
    PIDController strafeController = new PIDController(0.04, 0, 0, 0, autoStrafer, autoStrafer);

    public void robotInit() {
        elevator.setSelectedSensorPosition(0);

        frontLeft.setInverted(true);
        backRight.setInverted(true);
        frontRight.setInverted(true);
        backLeft.setInverted(true);

        compressor.clearAllPCMStickyFaults();
        compressor.setClosedLoopControl(true);
        myDrive.setDeadband(.1);
        turnController.setInputRange(-180.0, 180.0);
        turnController.setOutputRange(-1, 1);
        turnController.setAbsoluteTolerance(kToleranceDegrees);
        turnController.setContinuous(true);
        turnController.enable();
        turnController.setSetpoint(0);

//        strafeController.setInputRange(-1000, 1000);
        strafeController.setOutputRange(-1, 1);
        strafeController.setAbsoluteTolerance(5);
        strafeController.setContinuous(false);
        strafeController.enable();
        strafeController.setSetpoint(0);
    }

    public void autonomousInit(){
        turnController.enable();
        myDrive.setSafetyEnabled(true);
    }

    public void autonomousPeriodic(){
        while (isAutonomous()) {
            run();
            smartDash();
            Timer.delay(.005);
        }
    }

    public void teleopInit(){
        turnController.enable();
        myDrive.setSafetyEnabled(true);
    }

    public void teleopPeriodic() {
        while(isOperatorControl()) { // avoid extra teleop baggage
            run();
            smartDash();
            Timer.delay(.005); // a bit odd, but told to not remove
        }
    }

    public void run() {
        ntReader();
        hatchMech();
        deployHatchMech();
        reset();
        spinArms();
        adjustElevator();
        lifter.run();
        runIntake();
        centricToggle();

        if (controller.getRawButton(1)) {
            autoStrafer.run();
            return;
        }

        if (!ahrs.isConnected()) {
            isFieldCentric = false;
        }

        if (controller.getTriggerAxis(leftHand) > 0.05 ^ controller.getTriggerAxis(rightHand) > 0.05) {
            strafe();
            snapToAngle();
        } else if (isFieldCentric) {
            driveCentric();
            snapToAngle();
        } else {
            driveStandard();
        }
    }

    private void ntReader() {
        NetworkTableInstance inst = NetworkTableInstance.getDefault();
        NetworkTable table = inst.getTable("vision");
        NetworkTableEntry zeroX = table.getEntry("zeroX");
        NetworkTableEntry zeroY = table.getEntry("zeroY");
        NetworkTableEntry oneX = table.getEntry("oneX");
        NetworkTableEntry oneY = table.getEntry("oneY");
        inst.startServer();
        inst.setServerTeam(4131);
        this.zeroCenterX = zeroX.getDouble(0);
        this.zeroCenterY = zeroY.getDouble(0);
        this.oneCenterX = oneX.getDouble(0);
        this.oneCenterY = oneY.getDouble(0);
    }

    public void smartDash() {
        SmartDashboard.putBoolean("isCentric", isFieldCentric);

        SmartDashboard.putNumber("Target", turnController.getSetpoint());
        SmartDashboard.putNumber("Set Point", turnController.getSetpoint());
        SmartDashboard.putBoolean("onTarget", turnController.onTarget());
        SmartDashboard.putNumber("Rotate to Angle Rate", rotateToAngleRate);
        SmartDashboard.putNumber("Get Angle", ahrs.getAngle());

        SmartDashboard.putBoolean("NavX Connected", ahrs.isConnected());

        SmartDashboard.putBoolean("Arms Up", armsUp);

        SmartDashboard.putNumber("zeroX", zeroCenterX);
        SmartDashboard.putNumber("zeroY", zeroCenterY);
        SmartDashboard.putNumber("oneX", oneCenterX);
        SmartDashboard.putNumber("oneY", oneCenterY);

        double P = SmartDashboard.getNumber("strafe_P", 0);
        double I = SmartDashboard.getNumber("strafe_I", 0);
        double D = SmartDashboard.getNumber("strafe_D", 0);
        strafeController.setPID(P, I, D);
    }

    @Override
    public void pidWrite(double output) {
        if (turnController.onTarget()) {
            rotateToAngleRate = 0;
        } else {
            rotateToAngleRate = output;
        }
    }

    public void reset(){
        if (controller.getRawButton(3)) {
            ahrs.reset();
        }
    }

    public void centricToggle() {
        if (controller.getRawButtonReleased(5)) {
            isFieldCentric = !isFieldCentric;
        }
    }

    public void snapToAngle(){
        if (controller.getPOV() == -1) { // no controller POV pressed
            hadDoublePOV = false;
            return;
        }

        int controllerPOV = controller.getPOV();

        if (controllerPOV > 180) {
            controllerPOV -= 360;
        }

        if (controllerPOV % 90 != 0) { // controller POV is at mixed angle
            hadDoublePOV = true;
            turnController.setSetpoint(controllerPOV);
        } else if (!hadDoublePOV) { // controller POV only has one pressed AND no double POV pressed yet
            turnController.setSetpoint(controllerPOV);
        }
    }

    public void strafe() {
        if(controller.getTriggerAxis(leftHand) > 0.05) {
            myDrive.driveCartesian(-controller.getTriggerAxis(leftHand), 0, controller.getX(rightHand));
        } else {
            myDrive.driveCartesian(controller.getTriggerAxis(rightHand), 0, controller.getX(rightHand));
        }
    }

    public void deployHatchMech() {
        if (isHatchDown) {
            hatchDeploy.set(DoubleSolenoid.Value.kForward);
        } else {
            hatchDeploy.set(DoubleSolenoid.Value.kReverse);
        }
        if (secondary.getRawButtonReleased(5)) {
            isHatchDown = !isHatchDown;
        }
    }

    public void spinArms(){
        if(intakeActive){
            leftArm.set(-.25);
            rightArm.set(.25);
        } else {
            leftArm.set(0);
            rightArm.set(0);
        }
    }

    public void driveCentric(){
        myDrive.driveCartesian(-controller.getX(leftHand), controller.getY(leftHand), rotateToAngleRate, ahrs.getAngle() - 2*turnController.getSetpoint());
//        myDrive.driveCartesian(controller.getY(leftHand), controller.getX(leftHand), rotateToAngleRate, ahrs.getAngle());
    }

    private void adjustElevator(){
        SmartDashboard.putNumber("elevator Encoder", elevator.getSelectedSensorPosition());

        if(secondary.getRawButton(4)) {
            lifter.setTarget(11300);
        } else if(secondary.getRawButton(3)) {
            lifter.setTarget(0);
        }
    }

    private void runIntake() {
        if (secondary.getBButtonReleased()) {
            intakeActive = !intakeActive;
        }

        if (intakeActive) {
            intake.set(.25);
        } else if (secondary.getTriggerAxis(leftHand) > 0.05 || secondary.getTriggerAxis(rightHand) > 0.05) {
            intake.set(-1);
        } else {
            intake.set(0);
        }

        if (secondary.getStartButtonReleased()) {
            armsUp = !armsUp;
        }

        if (!armsUp) {
             armsDeploy.set(DoubleSolenoid.Value.kForward);
         } else {
             armsDeploy.set(DoubleSolenoid.Value.kReverse);
         }
    }

    private void hatchMech(){
        if(secondary.getRawButton(1)){
            hatchMechanism.set(DoubleSolenoid.Value.kForward);
        } else {
            hatchMechanism.set(DoubleSolenoid.Value.kReverse);
        }
    }

    public void driveStandard() {
        myDrive.driveCartesian(controller.getX(leftHand), -controller.getY(leftHand), controller.getX(rightHand));
    }

    public void strafeCentric(double val) { //TODO: test
        myDrive.driveCartesian(val, 0, rotateToAngleRate, ahrs.getAngle() - 2*turnController.getSetpoint());
    }

    private class AutoStrafer implements PIDSource, PIDOutput {
        PIDSourceType pidSourceType = PIDSourceType.kDisplacement;
        double pidOut = 0;

        public void run() {
            strafeCentric(pidOut);
        }

        @Override
        public void pidWrite(double output) {
            pidOut = output;
        }

        @Override
        public void setPIDSourceType(PIDSourceType pidSource) {
            this.pidSourceType = pidSource;
        }

        @Override
        public PIDSourceType getPIDSourceType() {
            return pidSourceType;
        }

        @Override
        public double pidGet() {
            if (pidSourceType == PIDSourceType.kDisplacement) {
                // assumes 0 is center of frame
                return (zeroCenterX + oneCenterX) / (oneCenterX - zeroCenterX);
            } else {
                // I don't really care about velocity for these... I think.
                // TODO: If it doesn't work, may need to implement kVelocity
                return 0;
            }
        }
    }
}