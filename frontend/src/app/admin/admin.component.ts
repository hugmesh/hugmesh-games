import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AdminService } from '../services/admin.service';
import { AdminStats } from '../models/admin-stats.model';
import { PlayerSession } from '../models/session.model';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss',
})
export class AdminComponent implements OnInit {
  private readonly admin = inject(AdminService);

  stats$: Observable<AdminStats | null> = of(null);
  sessions$: Observable<PlayerSession[]> = of([]);
  errorMessage = '';

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.errorMessage = '';
    this.stats$ = this.admin.getStats().pipe(
      catchError(err => {
        this.errorMessage = err.status === 403
          ? 'Admin access required — log in with an isAdmin account.'
          : 'Failed to load stats.';
        return of(null);
      })
    );
    this.sessions$ = this.admin.getSessions().pipe(catchError(() => of([])));
  }

  kick(casdoorId: string): void {
    if (!confirm(`Force-disconnect ${casdoorId}?`)) return;
    this.admin.kickPlayer(casdoorId).pipe(catchError(() => of(null))).subscribe(() => this.refresh());
  }
}
