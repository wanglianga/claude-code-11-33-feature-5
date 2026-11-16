import { createRouter, createWebHashHistory } from 'vue-router'
import IngestionView from './views/IngestionView.vue'
import RunsView from './views/RunsView.vue'
import RunDetailView from './views/RunDetailView.vue'
import BatchTraceView from './views/BatchTraceView.vue'
import ReleasesView from './views/ReleasesView.vue'
import ExternalView from './views/ExternalView.vue'
import RegulatoryView from './views/RegulatoryView.vue'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/runs' },
    { path: '/ingestion', component: IngestionView, name: 'ingestion' },
    { path: '/runs', component: RunsView, name: 'runs' },
    { path: '/runs/:id', component: RunDetailView, name: 'run-detail', props: true },
    { path: '/runs/:id/batches/:batchCode', component: BatchTraceView, name: 'batch-trace', props: true },
    { path: '/releases', component: ReleasesView, name: 'releases' },
    { path: '/regulatory', component: RegulatoryView, name: 'regulatory' },
    { path: '/external', component: ExternalView, name: 'external' }
  ]
})
