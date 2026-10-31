import { httpResource, HttpResourceRef } from '@angular/common/http';
import { Signal } from '@angular/core';
import { OverviewView } from './overview.model';

export function getOverviewResource(
  hours: Signal<number>,
): HttpResourceRef<OverviewView | undefined> {
  return httpResource<OverviewView>(() => `/admin/api/overview?hours=${hours()}`);
}
