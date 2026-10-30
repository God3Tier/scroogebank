package bank.clienthealth.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SecurityProbeController {
    @GetMapping("/probe/read")
    @PreAuthorize("@taskAuthorization.canRead(authentication)")
    String read() {
        return "ok";
    }

    @GetMapping("/probe/mutate")
    @PreAuthorize("@taskAuthorization.canMutate(authentication)")
    String mutate() {
        return "ok";
    }
}
