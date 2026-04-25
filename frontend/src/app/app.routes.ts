import { Routes } from '@angular/router';
import { HomeComponent } from './home/home.component';
import { AboutComponent } from './about/about.component';
import { LoginComponent } from './login/login.component';
import { AdminComponent } from './admin/admin.component';
import { GameUploadComponent } from './admin/game-upload.component';
import { PlayerDashboardComponent } from './player-dashboard/player-dashboard.component';
import { authGuard } from './auth.guard';

export const routes: Routes = [
  { path: '', component: HomeComponent, canActivate: [authGuard] },
  { path: 'dashboard', component: PlayerDashboardComponent, canActivate: [authGuard] },
  { path: 'login', component: LoginComponent },
  { path: 'about', component: AboutComponent },
  { path: 'admin', component: AdminComponent, canActivate: [authGuard] },
  { path: 'admin/games', component: GameUploadComponent, canActivate: [authGuard] },
  { path: '**', redirectTo: '' },
];
