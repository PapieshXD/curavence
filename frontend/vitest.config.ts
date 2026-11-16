import { defineConfig } from 'vitest/config';

// Keep coverage runs stable on laptops and two-core GitHub runners.
export default defineConfig({ test: { maxWorkers: 2 } });
