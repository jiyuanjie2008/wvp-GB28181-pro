-- Add device_type column for IAM sync device type (BWC, VEHICLE, FIXED_CAMERA, DRONE)
ALTER TABLE wvp_device ADD COLUMN device_type VARCHAR(32) DEFAULT NULL;
