package com.genersoft.iot.vmp.jxt.identity.mapper;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DeviceIdentityMapper {

    @Insert("INSERT INTO wvp_device (" +
            "device_id, custom_name, sip_ha1, charset, media_server_id, " +
            "ssrc_check, geo_coord_sys, as_message_channel, broadcast_push_after_ack, " +
            "heart_beat_interval, heart_beat_count, disabled, activated, password, expires, " +
            "create_time, update_time, on_line, stream_mode, sdp_ip, server_id, device_type" +
            ") VALUES (" +
            "#{deviceId}, #{name}, #{sipHa1}, #{charset}, #{mediaServerId}, " +
            "#{ssrcCheck}, #{geoCoordSys}, #{asMessageChannel}, #{broadcastPushAfterAck}, " +
            "#{heartBeatInterval}, #{heartBeatCount}, #{disabled}, #{activated}, #{password}, #{expires}, " +
            "#{createTime}, #{updateTime}, #{onLine}, #{streamMode}, #{sdpIp}, #{serverId}, #{deviceType}" +
            ")")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertDevice(Device device);

    @Update({"<script>",
            "UPDATE wvp_device SET update_time=#{updateTime}, sip_ha1=#{sipHa1}",
            "<if test='name != null'>, custom_name=#{name}</if>",
            "<if test='charset != null'>, charset=#{charset}</if>",
            "<if test='mediaServerId != null'>, media_server_id=#{mediaServerId}</if>",
            "<if test='ssrcCheck != null'>, ssrc_check=#{ssrcCheck}</if>",
            "<if test='geoCoordSys != null'>, geo_coord_sys=#{geoCoordSys}</if>",
            "<if test='asMessageChannel != null'>, as_message_channel=#{asMessageChannel}</if>",
            "<if test='broadcastPushAfterAck != null'>, broadcast_push_after_ack=#{broadcastPushAfterAck}</if>",
            "<if test='heartBeatInterval != null'>, heart_beat_interval=#{heartBeatInterval}</if>",
            "<if test='heartBeatCount != null'>, heart_beat_count=#{heartBeatCount}</if>",
            "<if test='disabled != null'>, disabled=#{disabled}</if>",
            "<if test='activated != null'>, activated=#{activated}</if>",
            "<if test='streamMode != null'>, stream_mode=#{streamMode}</if>",
            "<if test='sdpIp != null'>, sdp_ip=#{sdpIp}</if>",
            "<if test='deviceType != null'>, device_type=#{deviceType}</if>",
            " WHERE device_id=#{deviceId}",
            "</script>"})
    int updateDevice(Device device);

    // --- Idempotency log (D4 Option B: no processing state) ---

    @Insert("INSERT INTO wvp_idempotency_log (idempotency_key, operation, device_id, status) " +
            "VALUES (#{idempotencyKey}, #{operation}, #{deviceId}, 'success')")
    int tryInsertIdempotencyLog(@Param("idempotencyKey") String key,
                                @Param("operation") String operation,
                                @Param("deviceId") String deviceId);

    @Delete("DELETE FROM wvp_idempotency_log WHERE idempotency_key = #{key}")
    int deleteIdempotencyLog(@Param("key") String key);

    @Delete("DELETE FROM wvp_idempotency_log WHERE created_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int cleanOldEntries(@Param("days") int days);

    @Update("UPDATE wvp_device SET device_type=#{deviceType} WHERE device_id=#{deviceId}")
    int updateDeviceType(@Param("deviceId") String deviceId, @Param("deviceType") String deviceType);
}
