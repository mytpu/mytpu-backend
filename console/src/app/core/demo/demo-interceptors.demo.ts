import { HttpInterceptorFn } from '@angular/common/http';
import { demoInterceptor } from './demo.interceptor';

export const DEMO_INTERCEPTORS: HttpInterceptorFn[] = [demoInterceptor];
