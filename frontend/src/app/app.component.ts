import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from './services/auth.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  get isLoggedIn(): boolean { return this.auth.isAuthenticated(); }
  get isAdmin(): boolean    { return this.auth.isAdmin(); }
  get displayName(): string | null { return this.auth.displayName(); }

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
