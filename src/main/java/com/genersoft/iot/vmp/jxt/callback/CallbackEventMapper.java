package com.genersoft.iot.vmp.jxt.callback;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CallbackEventMapper {

    @Insert("INSERT INTO wvp_callback_events (event_id, event_type, device_id, payload_json, sent_at, ack_attempts, status) " +
            "VALUES (#{eventId}, #{eventType}, #{deviceId}, #{payloadJson}, #{sentAt}, #{ackAttempts}, #{status})")
    int insert(CallbackEvent event);

    @Update("UPDATE wvp_callback_events SET status = #{status}, acked_at = #{ackedAt}, " +
            "ack_attempts = #{ackAttempts}, last_http_code = #{lastHttpCode} WHERE event_id = #{eventId}")
    int updateStatus(@Param("eventId") String eventId, @Param("status") String status,
                     @Param("ackedAt") LocalDateTime ackedAt, @Param("ackAttempts") int ackAttempts,
                     @Param("lastHttpCode") Integer lastHttpCode);

    @Select("SELECT * FROM wvp_callback_events WHERE status = 'pending' AND ack_attempts < 5 ORDER BY sent_at ASC")
    List<CallbackEvent> findPendingForRetry();

    @Delete("DELETE FROM wvp_callback_events WHERE status = 'acked' AND acked_at < #{cutoff}")
    int cleanAckedBefore(@Param("cutoff") LocalDateTime cutoff);
}
