package bank.engagement.notifications;

import bank.engagement.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/engagement/preferences")
@RequiredArgsConstructor
public class PreferencesController {

    private final PreferencesService preferences;
    private final CurrentUser currentUser;

    @GetMapping
    public Preferences get() {
        return preferences.get(currentUser.subject());
    }

    @PutMapping
    public Preferences update(@Valid @RequestBody Preferences requested) {
        return preferences.update(currentUser.subject(), requested);
    }
}
