package com.playball.kbopredictor.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/reconciliation")
public class AdminReconciliationController {
    private final FinancialReconciliationService reconciliation;
    @GetMapping("/users/{userId}")
    public ReconciliationResult user(@PathVariable long userId) { return reconciliation.checkUser(userId); }
    @GetMapping("/games/{gameId}")
    public ReconciliationResult game(@PathVariable long gameId) { return reconciliation.checkGame(gameId); }
}
