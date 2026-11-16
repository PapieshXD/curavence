export type WorkspaceRole = 'admin' | 'patient' | 'doctor' | 'therapy';

export type StatusTone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

export interface NavigationItem {
  id: string;
  label: string;
  icon: string;
}

export interface WorkspaceConfig {
  label: string;
  shortLabel: string;
  userName: string;
  title: string;
  subtitle: string;
  navigation: NavigationItem[];
}
