package org.usfirst.frc.team1318.robot.vision.pipelines;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.usfirst.frc.team1318.robot.common.wpilibmocks.ITimer;
import org.usfirst.frc.team1318.robot.vision.VisionConstants;
import org.usfirst.frc.team1318.robot.vision.helpers.ContourHelper;
import org.usfirst.frc.team1318.robot.vision.helpers.HSVFilter;
import org.usfirst.frc.team1318.robot.vision.helpers.ImageUndistorter;

import edu.wpi.cscore.CvSource;
import edu.wpi.first.wpilibj.CameraServer;

public class HSVShooterCenterPipeline implements ICentroidVisionPipeline
{
    private final ITimer timer;
    private final boolean shouldUndistort;
    private final ImageUndistorter undistorter;
    private final HSVFilter hsvFilter;

    private final CvSource frameInput;
    private final CvSource hsvOutput;

    // measured values
    private Point center;
    private Double measuredAngleX;
    private Double distanceFromRobot;

    // FPS Measurement
    private long analyzedFrameCount;
    private double lastMeasuredTime;
    private double lastFpsMeasurement;

    // active status
    private volatile boolean isActive;

    /**
     * Initializes a new instance of the HSVShooterCenterPipeline class.
     * @param timer to use for any timing purposes
     * @param shouldUndistort whether to undistort the image or not
     */
    public HSVShooterCenterPipeline(
        ITimer timer,
        boolean shouldUndistort)
    {
        this.shouldUndistort = shouldUndistort;

        this.undistorter = new ImageUndistorter();
        this.hsvFilter = new HSVFilter(VisionConstants.LIFECAM_HSV_FILTER_LOW, VisionConstants.LIFECAM_HSV_FILTER_HIGH);

        this.center = null;
        this.measuredAngleX = null;
        this.distanceFromRobot = null;

        this.analyzedFrameCount = 0;
        this.timer = timer;
        this.lastMeasuredTime = this.timer.get();

        this.isActive = true;

        if (VisionConstants.DEBUG && VisionConstants.DEBUG_OUTPUT_SHOOTER_FRAMES)
        {
            this.frameInput = CameraServer.getInstance().putVideo("s.input", VisionConstants.LIFECAM_CAMERA_RESOLUTION_X, VisionConstants.LIFECAM_CAMERA_RESOLUTION_Y);
            this.hsvOutput = CameraServer.getInstance().putVideo("s.hsv", VisionConstants.LIFECAM_CAMERA_RESOLUTION_X, VisionConstants.LIFECAM_CAMERA_RESOLUTION_Y);
        }
        else
        {
            this.frameInput = null;
            this.hsvOutput = null;
        }
    }

    /**
     * Process a single image frame
     * @param frame image to analyze
     */
    @Override
    public void process(Mat image)
    {
        if (VisionConstants.DEBUG)
        {
            if (VisionConstants.DEBUG_SAVE_FRAMES && this.analyzedFrameCount % VisionConstants.DEBUG_FRAME_OUTPUT_GAP == 0)
            {
                Imgcodecs.imwrite(String.format("%simage%d-1.undistorted.jpg", VisionConstants.DEBUG_OUTPUT_FOLDER, this.analyzedFrameCount), image);
            }

            if (VisionConstants.DEBUG_OUTPUT_SHOOTER_FRAMES)
            {
                this.frameInput.putFrame(image);
            }
        }

        if (!this.isActive)
        {
            return;
        }

        this.analyzedFrameCount++;
        if (VisionConstants.DEBUG && VisionConstants.DEBUG_PRINT_OUTPUT && this.analyzedFrameCount % VisionConstants.DEBUG_FPS_AVERAGING_INTERVAL == 0)
        {
            double now = this.timer.get();
            double elapsedTime = now - this.lastMeasuredTime;

            this.lastFpsMeasurement = ((double)VisionConstants.DEBUG_FPS_AVERAGING_INTERVAL) / elapsedTime;
            this.lastMeasuredTime = this.timer.get();
        }

        // first, undistort the image.
        Mat undistortedImage;
        if (this.shouldUndistort)
        {
            image = this.undistorter.undistortFrame(image);
        }

        // save the undistorted image for possible output later...
        if (this.shouldUndistort)
        {
            undistortedImage = image.clone();
        }
        else
        {
            undistortedImage = image;
        }

        // second, filter HSV
        image = this.hsvFilter.filterHSV(image);
        if (VisionConstants.DEBUG)
        {
            if (VisionConstants.DEBUG_SAVE_FRAMES && this.analyzedFrameCount % VisionConstants.DEBUG_FRAME_OUTPUT_GAP == 0)
            {
                Imgcodecs.imwrite(String.format("%simage%d-2.hsvfiltered.jpg", VisionConstants.DEBUG_OUTPUT_FOLDER, this.analyzedFrameCount), image);
            }

            if (VisionConstants.DEBUG_OUTPUT_SHOOTER_FRAMES)
            {
                this.hsvOutput.putFrame(image);
            }
        }

        // third, find the largest contour.
        MatOfPoint[] largestContours = ContourHelper.findTwoLargestContours(image, VisionConstants.CONTOUR_MIN_AREA);
        MatOfPoint largestContour = largestContours[0];
        MatOfPoint secondLargestContour = largestContours[1];

        if (largestContour == null)
        {
            if (VisionConstants.DEBUG && VisionConstants.DEBUG_PRINT_OUTPUT && VisionConstants.DEBUG_PRINT_ANALYZER_DATA)
            {
                System.out.println("could not find any contour");
            }
        }

        // fourth, find the center of mass for the largest two contours
        Point largestCenterOfMass = null;
        Point secondLargestCenterOfMass = null;
        Rect largestBoundingRectangle = null;
        Rect secondLargestBoundingRectangle = null;
        if (largestContour != null)
        {
            largestCenterOfMass = ContourHelper.findCenterOfMass(largestContour);
            largestBoundingRectangle = Imgproc.boundingRect(largestContour);
            largestContour.release();
        }

        if (secondLargestContour != null)
        {
            secondLargestCenterOfMass = ContourHelper.findCenterOfMass(secondLargestContour);
            secondLargestBoundingRectangle = Imgproc.boundingRect(secondLargestContour);
            secondLargestContour.release();
        }

        if (VisionConstants.DEBUG)
        {
            if (VisionConstants.DEBUG_PRINT_OUTPUT && VisionConstants.DEBUG_PRINT_ANALYZER_DATA)
            {
                if (largestCenterOfMass == null)
                {
                    System.out.println("couldn't find the center of mass!");
                }
                else
                {
                    System.out.println(String.format("Center of mass: %f, %f", largestCenterOfMass.x, largestCenterOfMass.y));
                }

                if (secondLargestCenterOfMass == null)
                {
                    System.out.println("couldn't find the center of mass!");
                }
                else
                {
                    System.out.println(String.format("Center of mass: %f, %f", secondLargestCenterOfMass.x, secondLargestCenterOfMass.y));
                }
            }
        }

        undistortedImage.release();

        Point upperCenterOfMass;
        Rect upperBoundingRectangle;
        if (largestCenterOfMass != null && secondLargestCenterOfMass != null && secondLargestCenterOfMass.y < largestCenterOfMass.y)
        {
            upperCenterOfMass = secondLargestCenterOfMass;
            upperBoundingRectangle = secondLargestBoundingRectangle;
        }
        else
        {
            upperCenterOfMass = largestCenterOfMass;
            upperBoundingRectangle = largestBoundingRectangle;
        }

        if (upperCenterOfMass == null)
        {
            this.center = null;
            this.measuredAngleX = null;
            this.distanceFromRobot = null;
            return;
        }

        this.center = upperCenterOfMass;

        double xOffsetMeasured = upperCenterOfMass.x - VisionConstants.LIFECAM_CAMERA_CENTER_WIDTH;
        this.measuredAngleX = -(Math.atan(xOffsetMeasured / VisionConstants.LIFECAM_CAMERA_FOCAL_LENGTH_X) * VisionConstants.RADIANS_TO_ANGLE - VisionConstants.SHOOTER_CAMERA_HORIZONTAL_MOUNTING_ANGLE);

        double yOffsetMeasured = upperCenterOfMass.y - VisionConstants.LIFECAM_CAMERA_CENTER_HEIGHT;
        double measuredAngleY = Math.atan(yOffsetMeasured / VisionConstants.LIFECAM_CAMERA_FOCAL_LENGTH_Y) * VisionConstants.RADIANS_TO_ANGLE;

        double angleY = measuredAngleY + VisionConstants.SHOOTER_CAMERA_VERTICAL_MOUNTING_ANGLE;
        this.distanceFromRobot = VisionConstants.SHOOTER_CAMERA_TO_RETROREFLECTIVE_TAPE_HEIGHT / Math.tan(angleY * VisionConstants.ANGLE_TO_RADIANS);
    }

    public void setActivation(boolean isActive)
    {
        this.isActive = isActive;
    }

    public boolean isActive()
    {
        return this.isActive;
    }

    public Point getCenter()
    {
        return this.center;
    }

    public Double getDesiredAngleX()
    {
        return 0.0;
    }

    public Double getMeasuredAngleX()
    {
        return this.measuredAngleX;
    }

    public Double getRobotDistance()
    {
        return this.distanceFromRobot;
    }

    public double getFps()
    {
        return this.lastFpsMeasurement;
    }
}
