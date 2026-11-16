<template>
  <div class="layout">
    <aside class="sidebar">
      <h1>药品批次谱系<br/>数据质量与召回影响服务</h1>
      <div class="sub">来源断言 / 发布范围分别版本化 · 截止时间可复现</div>
      <nav>
        <router-link to="/runs">范围核验台</router-link>
        <router-link to="/ingestion">接入登记台</router-link>
        <router-link to="/releases">发布版本管理</router-link>
        <router-link to="/regulatory">监管答复</router-link>
        <router-link to="/external">外部接口（脱敏）</router-link>
      </nav>
      <div class="small" style="margin-top:26px;line-height:1.7">
        技术栈：Vue 3 + Spring Boot + PostgreSQL<br/>
        本系统不隔离、销毁、退运或调拨药品，<br/>不下达机构通知，不生成付款/审批决定。
      </div>
    </aside>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>
