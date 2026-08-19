# Agent Drive 开发任务入口
.PHONY: help install dev-backend dev-frontend test build clean

help:
	@echo "Agent Drive 命令:"
	@echo "  make install       安装 Java + 前端依赖"
	@echo "  make dev-backend   启动 Java API :8000"
	@echo "  make dev-frontend  启动前端 next dev :3333"
	@echo "  make test          运行 Java + 前端测试"
	@echo "  make build         Java artifact + 前端生产构建"
	@echo "  make clean         清理构建产物"

install:
	cd backend && mvn -q dependency:go-offline
	cd frontend && npm install

dev-backend:
	cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=db,java-chat -Dspring-boot.run.arguments="--app.mode=api"

dev-frontend:
	cd frontend && NEXT_PUBLIC_API_BASE=http://localhost:8000/api/v1 npm run dev -p 3333

test:
	cd backend && mvn -q test
	cd frontend && npm test

build:
	cd backend && mvn -q -DskipTests package
	cd frontend && npm run build

clean:
	cd backend && mvn -q clean
	rm -rf frontend/out
