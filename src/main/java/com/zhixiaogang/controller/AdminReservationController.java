package com.zhixiaogang.controller;

import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.classroom.AdminReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 管理端教室预约管理：列表、批准、拒绝与撤销；业务已下沉至 AdminReservationService。 */
@Controller
@RequestMapping("/admin")
public class AdminReservationController {

    @Autowired
    private AdminReservationService adminReservationService;

    @Autowired
    private OperationLogService operationLogService;

    @GetMapping("/reservations")
    @ResponseBody
    public Map<String, Object> getReservations(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String date,
                                               @RequestParam(required = false) String keyword) {
        return adminReservationService.listReservations(page, size, status, date, keyword);
    }

    @PostMapping("/reservations/{id}/approve")
    @ResponseBody
    public Map<String, Object> approveReservation(@PathVariable Long id) {
        Map<String, Object> result = adminReservationService.approve(id);
        String logDetail = String.valueOf(result.remove(AdminReservationService.LOG_DETAIL_KEY));
        return AdminWebSupport.logged(operationLogService, result, "approve", "reservation", id, logDetail);
    }

    @PostMapping("/reservations/{id}/reject")
    @ResponseBody
    public Map<String, Object> rejectReservation(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = adminReservationService.reject(id, body);
        String logDetail = String.valueOf(result.remove(AdminReservationService.LOG_DETAIL_KEY));
        return AdminWebSupport.logged(operationLogService, result, "reject", "reservation", id, logDetail);
    }

    /** 撤销已批准的教室预约（approved→cancelled），撤销后将撤销原因随通知发给发起人 */
    @PostMapping("/reservations/{id}/cancel")
    @ResponseBody
    public Map<String, Object> cancelReservation(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = adminReservationService.cancel(id, body);
        String logDetail = String.valueOf(result.remove(AdminReservationService.LOG_DETAIL_KEY));
        return AdminWebSupport.logged(operationLogService, result, "cancel", "reservation", id, logDetail);
    }
}
