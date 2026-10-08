package com.sysadminanywhere.m3.messaging.api;
import com.sysadminanywhere.m3.messaging.service.OperationsService;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
@RestController @Profile("!worker") @RequestMapping("/api/v1/operations")
public class OperationsApi {
    private final OperationsService operations;public OperationsApi(OperationsService operations){this.operations=operations;}
    @GetMapping public OperationsService.Snapshot snapshot(){return operations.snapshot();}
    @GetMapping(value="/metrics",produces="text/plain;version=0.0.4;charset=utf-8") public String metrics(){return operations.prometheus();}
}
