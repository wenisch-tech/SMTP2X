package tech.wenisch.smtp2x.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class WebController {
  @ModelAttribute
  void layout(Model model, Authentication user) {
    model.addAttribute("user",user==null?"":user.getName());
    model.addAttribute("isAdmin",user!=null && user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")));
  }
  @GetMapping("/") String dashboard(){return "dashboard";}
  @GetMapping("/pending") String pending(){return "pending";}
  @GetMapping("/login") String login(){return "login";}
  @GetMapping("/actions") String actions(){return "actions";}
  @GetMapping("/rules") String rules(){return "rules";}
  @GetMapping("/messages") String messages(){return "messages";}
  @GetMapping("/deliveries") String deliveries(){return "deliveries";}
  @GetMapping("/audit") String audit(){return "audit";}
  @GetMapping("/administration") @PreAuthorize("hasRole('ADMIN')") String admin(){return "administration";}
}
