import { Routes } from '@angular/router';
import { MainLayout } from './layouts/main-layout/main-layout';
import { Login } from './features/auth/pages/login/login';
import { authGuard } from './core/services/auth-guard';

export const routes: Routes = [
  {
    path: 'login',
    component: Login,
  },
  {
    path: '',
    component: MainLayout,
    canActivate: [authGuard],
  },
  {
    path: '**',
    redirectTo: '',
  },
];
