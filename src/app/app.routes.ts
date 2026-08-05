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
    path: 'patients/:patientId/imaging',
    component: MainLayout,
    canActivate: [authGuard],
  },
  {
    path: 'patients/:patientId/imaging/upload',
    component: MainLayout,
    canActivate: [authGuard],
  },
  {
    path: 'patients/:patientId/mri',
    component: MainLayout,
    canActivate: [authGuard],
  },
  {
    path: 'patients/:patientId/mri/upload',
    component: MainLayout,
    canActivate: [authGuard],
  },
  {
    path: 'imaging-studies/:studyId',
    component: MainLayout,
    canActivate: [authGuard],
  },
  {
    path: 'imaging-studies/:studyId/viewer',
    component: MainLayout,
    canActivate: [authGuard],
  },
  {
    path: '**',
    redirectTo: '',
  },
];
